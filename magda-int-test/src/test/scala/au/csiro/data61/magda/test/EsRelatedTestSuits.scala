package au.csiro.data61.magda.test

import akka.stream.scaladsl.Source
import org.scalatest._
import au.csiro.data61.magda.api.DataSetSearchSpec
import au.csiro.data61.magda.api.FacetSpec
import au.csiro.data61.magda.api.LanguageAnalyzerSpec
import au.csiro.data61.magda.crawler.CrawlerApiSpec
import au.csiro.data61.magda.indexer.WebhookSpec
import au.csiro.data61.magda.search.elasticsearch.{DefaultIndices, IndexDefinition, Indices}
import au.csiro.data61.magda.spatial.{RegionLoader, RegionSource}
import au.csiro.data61.magda.test.api.BaseApiSpec
import spray.json.JsObject
import scala.concurrent.duration.DurationInt

class EsRelatedTestSuitsSpec extends Suites  (
  new DataSetSearchSpec,
  new FacetSpec,
  new LanguageAnalyzerSpec,
  new CrawlerApiSpec,
  new WebhookSpec
)
  with BaseApiSpec
  with BeforeAndAfterAll
{

  override def beforeAll() {
    println("beforeAll.........")
    if (!doesIndexExists(DefaultIndices.getIndex(config, Indices.DataSetsIndex))) {
      println("Setting up dataset indice")
      logger.info("Setting up dataset indice")
      val f = client.execute(
        IndexDefinition.dataSets.definition(DefaultIndices, config)
      )

      println("Finished up dataset indice")
      logger.info("Finished setting up regions")

      blockUntil("wait untill index created"){ ()=>
        f.isCompleted
      }
      println(f.value)
    }

    if (!doesIndexExists(DefaultIndices.getIndex(config, Indices.RegionsIndex))) {
      logger.info("Setting up regions")

      client.execute(
        IndexDefinition.regions.definition(DefaultIndices, config)
      ).await(90 seconds)

      val fakeRegionLoader = new RegionLoader {
        override def setupRegions(): Source[(RegionSource, JsObject), _] = Source.fromIterator(() => BaseApiSpec.indexedRegions.toIterator)
      }

      IndexDefinition.setupRegions(client, fakeRegionLoader, DefaultIndices).await(60 seconds)
      logger.info("Finished setting up regions")
      println("beforeAll end.........")
    }

    System.gc()
  }

  override def afterAll() {
    println("afterAll.........")
    System.gc()
  }

}
