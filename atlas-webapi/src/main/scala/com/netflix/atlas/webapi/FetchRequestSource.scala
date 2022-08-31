/*
 * Copyright 2014-2022 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.atlas.webapi

import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.NotUsed
import akka.actor.ActorRefFactory
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.HttpEntity.ChunkStreamPart
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.MediaTypes
import akka.http.scaladsl.model.StatusCodes
import akka.stream.Attributes
import akka.stream.FlowShape
import akka.stream.Inlet
import akka.stream.Outlet
import akka.stream.ThrottleMode
import akka.stream.scaladsl.Source
import akka.stream.stage.GraphStage
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.InHandler
import akka.stream.stage.OutHandler
import akka.util.ByteString
import akka.util.Timeout
import com.netflix.atlas.akka.DiagnosticMessage
import com.netflix.atlas.core.model.DataExpr
import com.netflix.atlas.core.model.EvalContext
import com.netflix.atlas.core.model.StatefulExpr
import com.netflix.atlas.core.model.TimeSeq
import com.netflix.atlas.core.model.TimeSeries
import com.netflix.atlas.eval.graph.GraphConfig
import com.netflix.atlas.eval.model.TimeSeriesMessage
import com.netflix.atlas.webapi.GraphApi.DataRequest
import com.netflix.atlas.webapi.GraphApi.DataResponse

/**
  * Provides the SSE data stream payload for a fetch response. Fetch is an alternative
  * to the graph API that is meant for accessing the data rather than rendering as an
  * image. The response can be partitioned across both the set of expressions and across
  * time to allow more flexibility on the backend for tradeoffs between latency and
  * intermediate overhead. The graph API enforces strict limits on the sizes.
  */
object FetchRequestSource {

  /**
    * Create an SSE source that can be used as the entity for the HttpResponse.
    */
  def apply(system: ActorRefFactory, graphCfg: GraphConfig): Source[ChunkStreamPart, NotUsed] = {
    import akka.pattern._
    import scala.concurrent.duration._

    val dbRef = system.actorSelection("/user/db")
    val chunks = {
      val step = graphCfg.roundedStepSize
      val (fstart, fend) = roundToStep(step, graphCfg.resStart, graphCfg.resEnd)
      EvalContext(fstart.toEpochMilli, fend.toEpochMilli, step)
        .partition(60 * step, ChronoUnit.MILLIS)
    }

    val heartbeatSrc = Source
      .repeat(DiagnosticMessage.info("heartbeat"))
      .throttle(1, 10.seconds, 1, ThrottleMode.Shaping)

    val dataSrc = Source(chunks)
      .flatMapConcat { chunk =>
        val req = DataRequest(graphCfg).copy(context = chunk)
        val future = ask(dbRef, req)(Timeout(30.seconds))
        Source
          .fromFuture(future)
          .collect {
            case DataResponse(data) => DataChunk(chunk, data)
          }
      }
      .via(new EvalFlow(graphCfg))
      .flatMapConcat(ts => Source(ts))
      .recover {
        case t: Throwable => DiagnosticMessage.error(t)
      }
      .merge(heartbeatSrc, eagerComplete = true)

    val closeSrc = Source.single(DiagnosticMessage.close)

    Source(List(dataSrc, closeSrc))
      .flatMapConcat(s => s)
      .map { msg =>
        val bytes = ByteString(s"$prefix${msg.toJson}$suffix")
        ChunkStreamPart(bytes)
      }
  }

  /**
    * Returns an HttpResponse with an entity that is generated by the fetch source.
    */
  def createResponse(system: ActorRefFactory, graphCfg: GraphConfig): HttpResponse = {
    val source = apply(system, graphCfg)
    HttpResponse(
      status = StatusCodes.OK,
      entity = HttpEntity.Chunked(MediaTypes.`text/event-stream`, source)
    )
  }

  private def isAllNaN(seq: TimeSeq, s: Long, e: Long, step: Long): Boolean = {
    require(s <= e, "start must be <= end")
    val end = e / step * step
    var t = s / step * step
    while (t < end) {
      if (!seq(t).isNaN) return false
      t += step
    }
    true
  }

  // SSE message prefix and suffix. Fetch is simple and just emits data items with JSON.
  private val prefix = "data: "
  private val suffix = "\n\n"

  private def roundToStep(step: Long, s: Instant, e: Instant): (Instant, Instant) = {
    val rs = roundToStep(step, s)
    val re = roundToStep(step, e)
    val adjustedStart = if (rs.equals(re)) rs.minusMillis(step) else rs
    adjustedStart -> re
  }

  private def roundToStep(step: Long, i: Instant): Instant = {
    Instant.ofEpochMilli(i.toEpochMilli / step * step)
  }

  /**
    * Keeps track of state for stateful operators across the execution. Note, that the data
    * must be broken down and processed in the correct time order for the state processing to
    * be correct. This operator should get created a single time for a given execution so that
    * state is maintained overall.
    */
  private class EvalFlow(graphCfg: GraphConfig)
      extends GraphStage[FlowShape[DataChunk, List[TimeSeriesMessage]]] {

    private val in = Inlet[DataChunk]("EvalFlow.in")
    private val out = Outlet[List[TimeSeriesMessage]]("EvalFlow.out")

    override val shape: FlowShape[DataChunk, List[TimeSeriesMessage]] = FlowShape(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
      new GraphStageLogic(shape) with InHandler with OutHandler {

        private var state = Map.empty[StatefulExpr, Any]

        override def onPush(): Unit = {
          val chunk = grab(in)
          val ts = graphCfg.exprs
            .flatMap { s =>
              val context = chunk.context.copy(state = state)
              val result = s.expr.eval(context, chunk.data)
              state = result.state
              result.data
                .filterNot(ts => isAllNaN(ts.data, context.start, context.end, context.step))
                .map(ts => TimeSeriesMessage(s, context, ts))
            }
          push(out, ts)
        }

        override def onPull(): Unit = {
          pull(in)
        }

        setHandlers(in, out, this)
      }
    }
  }

  private case class DataChunk(context: EvalContext, data: DataMap)

  type DataMap = Map[DataExpr, List[TimeSeries]]
}
