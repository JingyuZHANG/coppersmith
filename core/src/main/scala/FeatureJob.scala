package commbank.coppersmith

import com.twitter.scalding.{Config, Execution}
import com.twitter.scalding.typed._

import au.com.cba.omnia.maestro.api._

import Feature.{EntityId, Time}

trait FeatureJobConfig[S] {
  def featureSource: FeatureSource[S]
  def featureSink:   FeatureSink
}

abstract class SimpleFeatureJob extends MaestroJob {
  val attemptsExceeded = Execution.from(JobNeverReady)

  def generate[S](cfg:      Config => FeatureJobConfig[S],
                  features: FeatureSet[S]): Execution[JobStatus] =
    generate[S](cfg, generateOneToMany(features)_)

  def generate[S](cfg:      Config => FeatureJobConfig[S],
                  features: AggregationFeatureSet[S]): Execution[JobStatus] =
    generate[S](cfg, generateAggregate(features)_)

  def generate[S](
    cfg:      Config => FeatureJobConfig[S],
    transform: TypedPipe[S] => TypedPipe[FeatureValue[_]]
  ): Execution[JobStatus] = {
    for {
      conf     <- Execution.getConfig.map(cfg)
      source    = conf.featureSource
      input     = source.load(conf)
      values    = transform(input)
      _        <- conf.featureSink.write(values, conf)
    } yield JobFinished
  }

  private def generateOneToMany[S](features: FeatureSet[S])
                                  (input: TypedPipe[S]): TypedPipe[FeatureValue[_]] =
    input.flatMap(features.generate(_))

  // Should be able to take advantage of shapless' tuple support in combination with Aggregator.join
  // in order to run the aggregators in one pass over the input. Need to consider that features may
  // have different filter conditions though.
  private def generateAggregate[S](features: AggregationFeatureSet[S])
                                  (input: TypedPipe[S]): TypedPipe[FeatureValue[_]] = {

    val grouped: Grouped[(EntityId, Time), S] = input.groupBy(s => (features.entity(s), features.time(s)))
    features.aggregationFeatures.map(feature => {
      val name = feature.name
      // TODO: Unnecessarily traverses grouped when feature.where is None, however, there doesn't
      // appear to be a common supertype of Grouped and UnsortedGrouped with aggregate. One option
      // might be Either[Grouped, UnsortedGrouped].fold(_.aggregate(...), _.aggregate(...)).merge
      val filtered = grouped.filter { case (_, s) => feature.where.map(_(s)).getOrElse(true) }
      filtered.aggregate(feature.aggregator).toTypedPipe.map { case ((e, t), v) =>
        FeatureValue(e, name, v, t)
      }
    }).foldLeft(TypedPipe.from(List[FeatureValue[_]]()))(_ ++ _)
  }
}