package lila.memo

import ornicar.scalalib.Zero
import scala.concurrent.duration.Duration

/**
  * side effect throttler that allows X ops per Y unit of time
  */
final class RateLimit[K](
    credits: Int,
    duration: Duration,
    name: String,
    key: String,
    enforce: Boolean = true,
    log: Boolean = true
) {
  import RateLimit._

  private val storage = lila.memo.CacheApi.scaffeineNoScheduler
    .expireAfterWrite(duration)
    .build[K, (Cost, ClearAt)]()

  private def makeClearAt = nowMillis + duration.toMillis

  private lazy val logger  = lila.log("ratelimit").branch(name)
  private lazy val monitor = lila.mon.security.rateLimit(key)

  def chargeable[A](k: K, cost: Cost = 1, msg: => String = "")(
      op: Charge => A
  )(implicit default: Zero[A]): A =
    apply(k, cost, msg) { op(c => apply(k, c, s"charge: $msg")(())) }

  def apply[A](k: K, cost: Cost = 1, msg: => String = "")(op: => A)(implicit default: Zero[A]): A =
    storage getIfPresent k match {
      case None =>
        storage.put(k, cost -> makeClearAt)
        op
      case Some((a, clearAt)) if a < credits =>
        storage.put(k, (a + cost) -> clearAt)
        op
      case Some((_, clearAt)) if nowMillis > clearAt =>
        storage.put(k, cost -> makeClearAt)
        op
      case _ if enforce =>
        if (log) logger.info(s"$credits/$duration $k cost: $cost $msg")
        monitor.increment()
        default.zero
      case _ =>
        op
    }
}

object RateLimit {

  type Charge = Cost => Unit
  type Cost   = Int

  private type ClearAt = Long
}
