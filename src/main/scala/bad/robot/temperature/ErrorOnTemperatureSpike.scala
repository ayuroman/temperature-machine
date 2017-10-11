package bad.robot.temperature

import bad.robot.temperature.ErrorOnTemperatureSpike._

import scalaz.{-\/, \/}
import scala.collection.concurrent.TrieMap

object ErrorOnTemperatureSpike {

  private val spikePercentage = 30

  // negative numbers would be a decrease, which we'll ignore (use Math.abs if we change our mind later)
  def percentageIncrease(oldValue: Double, newValue: Double): Double = (newValue - oldValue) / oldValue * 100

  /**
    * @param delegate delegate writer
    * @return a [[TemperatureWriter]] that will produce an error (left disjunction) when a spike over [[spikePercentage]]
    *         is detected or pass through to the delegate if the system property `avoid.spikes` is not set.
    */
  def apply(delegate: TemperatureWriter): TemperatureWriter = {
    sys.props.get("avoid.spikes").map(_ => {
      println(s"Temperature spikes greater than $spikePercentage% will not be recorded")
      new ErrorOnTemperatureSpike(delegate)
    }).getOrElse(delegate)
  }
}

/**
  * This isn't atomic in terms of the `get` and `update` calls against the cached values. An value could be retrieved,
  * the `spikeBetween` check made whilst the cache contains an updated value.
  *
  * However, the cache structure is thread safe, so at worst, you may get out of date data.
  *
  * However, as we know that for every host, at most one call will be made every 30 seconds, there is no risk on
  * concurrent access for a particular sensor (the key to the cache).
  */
class ErrorOnTemperatureSpike(delegate: TemperatureWriter) extends TemperatureWriter {

  private val temperatures: TrieMap[String, Temperature] = TrieMap()

  def write(measurement: Measurement): Error \/ Unit = {

    val spiked = measurement.temperatures.flatMap(current => {
      temperatures.get(current.name) match {
        case Some(previous) if spikeBetween(current, previous) => List((current.name, previous, current.temperature))
        case _                                                 => Nil
      }
    })

    if (spiked.nonEmpty) {
      -\/(SensorSpikeError(spiked.map(_._1), previous = spiked.map(_._2), current = spiked.map(_._3)))
    } else {
      measurement.temperatures.foreach(current => temperatures.update(current.name, current.temperature))
      delegate.write(measurement)
    }
  }

  private def spikeBetween(reading: SensorReading, previous: Temperature) = {
    percentageIncrease(previous.celsius, reading.temperature.celsius) >= spikePercentage
  }

}
