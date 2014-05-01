package com.bokland.rubbercube.sliceanddice.es

import org.elasticsearch.action.search.SearchRequestBuilder
import com.bokland.rubbercube.sliceanddice.{RequestResult, SliceAndDice, ExecutionEngine}
import org.elasticsearch.client.transport.TransportClient
import com.bokland.rubbercube.marshaller.es.EsFilterMarshaller
import com.bokland.rubbercube.measure.es.EsAggregationQueryBuilder
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogram
import scala.collection.JavaConversions._
import org.elasticsearch.search.aggregations.Aggregation
import org.elasticsearch.search.aggregations.metrics.cardinality.Cardinality
import org.elasticsearch.index.query.QueryBuilders._
import org.elasticsearch.search.aggregations.metrics.sum.Sum
import org.elasticsearch.search.aggregations.metrics.avg.Avg
import org.elasticsearch.search.aggregations.metrics.valuecount.ValueCount
import com.bokland.rubbercube.measure.{DerivedMeasure, Measure}
import EsAggregationQueryBuilder._
import RequestResult._
import com.bokland.rubbercube.measure.DerivedMeasures.Div
import com.bokland.rubbercube.Dimension
import org.elasticsearch.search.aggregations.bucket.terms.Terms

/**
 * Created by remeniuk on 4/29/14.
 */
class EsExecutionEngine(client: TransportClient, index: String) extends ExecutionEngine[SearchRequestBuilder] {

  def buildRequest(sliceAndDice: SliceAndDice): SearchRequestBuilder = {
    import sliceAndDice._

    // return no documents
    val search = client.prepareSearch(index).setTypes(sliceAndDice.id).setSize(0)

    // add aggregations
    measures.foreach {
      case derivedMeasure: DerivedMeasure =>
        derivedMeasure.measures.map {
          measure =>
            search.addAggregation(buildAggregationQuery(measure, aggregations))
        }

      case measure: Measure =>
        search.addAggregation(buildAggregationQuery(measure, aggregations))
    }

    // add filters, if defined
    if (filters.size > 0) {

      val query = boolQuery()

      // find filters that should be applied to parent document
      val parentFilters = for {
        parentCubeId <- sliceAndDice.parentId.toIterable
        filter <- filters
        filterCubeId <- filter.dimension.cubeId if filterCubeId == parentCubeId
      } yield filter

      filters.toList.diff(parentFilters.toList).foreach {
        filter => query.must(EsFilterMarshaller.marshal(filter))
      }

      parentFilters.foreach {
        filter =>
          query.must(hasParentQuery(filter.dimension.cubeId.get,
            EsFilterMarshaller.marshal(filter)))
      }

      search.setQuery(query)

    }

    search
  }

  private def parseCategoryAggregationResult(aggregation: Aggregation): (String, Any) =
    aggregation match {
      case cardinality: Cardinality => aggregation.getName -> cardinality.getValue
      case sum: Sum => aggregation.getName -> sum.getValue
      case avg: Avg => aggregation.getName -> avg.getValue
      case count: ValueCount => aggregation.getName -> count.getValue
    }

  private def isBucketAggregation(aggregation: Aggregation): Boolean =
    aggregation.isInstanceOf[DateHistogram] || aggregation.isInstanceOf[Terms]

  def runQuery(query: SearchRequestBuilder): RequestResult = {
    val result = query.execute().get()

    var resultSet: Set[Map[String, Any]] = Set()

    // processes bucket aggregations
    def parseResults(aggregation: Aggregation, tuple: Map[String, Any] = Map()): Any = {

      aggregation match {

        case terms: Terms =>
          terms.getBuckets.foreach {
            bucket =>
            // if child aggregation is bucket aggregation, drill down
              if (bucket.getAggregations.forall(isBucketAggregation)) {
                bucket.getAggregations.foreach(parseResults(_, tuple + (aggregation.getName -> bucket.getKey)))
              } else {
                // if child aggregation is category aggregation, build tuple and add it to result
                resultSet = resultSet + ((tuple + (aggregation.getName -> bucket.getKey)) ++
                  bucket.getAggregations.map(parseCategoryAggregationResult).toMap)
              }
          }

        case dateHistogram: DateHistogram =>

          dateHistogram.getBuckets.foreach {
            bucket =>
            // if child aggregation is bucket aggregation, drill down
              if (bucket.getAggregations.forall(isBucketAggregation)) {
                bucket.getAggregations.foreach(parseResults(_, tuple + (aggregation.getName -> bucket.getKey)))
              } else {
                // if child aggregation is category aggregation, build tuple and add it to result
                resultSet = resultSet + ((tuple + (aggregation.getName -> bucket.getKey)) ++
                  bucket.getAggregations.map(parseCategoryAggregationResult).toMap)
              }
          }

      }
    }

    RequestResult {
      if (result.getAggregations.size == 1) {
        parseResults(result.getAggregations.head)
        resultSet.toSeq
      } else {
        // may only happen, if category aggregations are upper level, and there're no
        // bucket aggregations
        Seq(result.getAggregations.map(parseCategoryAggregationResult).toMap)
      }
    }
  }

  def joinResults(by: Seq[Dimension])(resultSets: Iterable[RequestResult]): RequestResult =
    RequestResult {
      resultSets.head.resultSet.map {
        leftTuple =>
          val valuesToJoinBy = leftTuple.filterKeys(by.map(_.name).contains)
          val rightTuples = resultSets.tail.toList.flatMap(_.find(valuesToJoinBy))

          joinTuples(leftTuple :: rightTuples)
      }
    }

  def applyDerivedMeasures(derivedMeasures: Iterable[DerivedMeasure])(result: RequestResult): RequestResult = {
    if (derivedMeasures.isEmpty) result
    else {
      val resultSet = for {
        tuple <- result.resultSet
        derivedMeasure <- derivedMeasures
      } yield derivedMeasure match {

          case Div(m1, m2, alias) =>
            val value = (for {
              v1 <- tuple.get(m1.name).map(_.toString.toDouble) if v1 > 0
              v2 <- tuple.get(m2.name).map(_.toString.toDouble) if v2 > 0
            } yield v1 / v2) getOrElse 0d

            tuple + (alias.getOrElse(derivedMeasure.name) -> value)

        }

      result.copy(resultSet = resultSet)
    }
  }

}
