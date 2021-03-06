package lila.fishnet

import org.joda.time.DateTime
import scalaz.Validation.FlatMap._

import chess.format.Uci
import JsonApi.Request.Evaluation
import lila.analyse.{ Analysis, Info }
import lila.game.GameRepo
import lila.tree.Eval

private final class AnalysisBuilder(evalCache: FishnetEvalCache) {

  def apply(client: Client, work: Work.Analysis, evals: List[Evaluation.OrSkipped]): Fu[Analysis] =
    partial(client, work, evals map some, isPartial = false)

  def partial(
    client: Client,
    work: Work.Analysis,
    evals: List[Option[Evaluation.OrSkipped]],
    isPartial: Boolean = true
  ): Fu[Analysis] = {

    GameRepo.game(work.game.id) zip evalCache.evals(work) flatMap {
      case (None, _) => fufail(AnalysisBuilder.GameIsGone(work.game.id))
      case (Some(game), cachedFull) =>
        /* remove first eval in partial analysis
         * to prevent the mobile app from thinking it's complete
         * https://github.com/veloce/lichobile/issues/722
         */
        val cached = if (isPartial) cachedFull - 0 else cachedFull
        GameRepo.initialFen(game) flatMap { initialFen =>
          def debug = s"${game.variant.key} analysis for ${game.id} by ${client.fullId}"
          chess.Replay(game.pgnMoves, initialFen, game.variant).flatMap(_.valid).fold(
            fufail(_),
            replay => UciToPgn(replay, Analysis(
              id = work.game.id,
              infos = makeInfos(mergeEvalsAndCached(work, evals, cached), work.game.uciList, work.startPly),
              startPly = work.startPly,
              uid = work.sender.userId,
              by = !client.lichess option client.userId.value,
              date = DateTime.now
            )) match {
              case (analysis, errors) =>
                errors foreach { e => logger.debug(s"[UciToPgn] $debug $e") }
                if (analysis.valid) {
                  if (!isPartial && analysis.emptyRatio >= 1d / 10)
                    fufail(s"${game.variant.key} analysis $debug has ${analysis.nbEmptyInfos} empty infos out of ${analysis.infos.size}")
                  else fuccess(analysis)
                } else fufail(s"${game.variant.key} analysis $debug is empty")
            }
          )
        }
    }
  }

  private def mergeEvalsAndCached(work: Work.Analysis, evals: List[Option[Evaluation.OrSkipped]], cached: Map[Int, Evaluation]): List[Option[Evaluation]] =
    evals.zipWithIndex.map {
      case (None, i) => cached get i
      case (Some(Right(eval)), i) => cached.getOrElse(i, eval).some
      case (_, i) => cached get i orElse {
        logger.error(s"Missing cached eval for skipped position at index $i in $work")
        none[Evaluation]
      }
    }

  private def makeInfos(evals: List[Option[Evaluation]], moves: List[Uci], startedAtPly: Int): List[Info] =
    (evals filterNot (_ ?? (_.isCheckmate)) sliding 2).toList.zip(moves).zipWithIndex map {
      case ((List(Some(before), Some(after)), move), index) => {
        val variation = before.cappedPvList match {
          case first :: rest if first != move => first :: rest
          case _ => Nil
        }
        val best = variation.headOption
        val info = Info(
          ply = index + 1 + startedAtPly,
          eval = Eval(
            after.score.cp,
            after.score.mate,
            best
          ),
          variation = variation.map(_.uci)
        )
        if (info.ply % 2 == 1) info.invert else info
      }
      case ((_, _), index) => Info(index + 1 + startedAtPly, Eval.empty)
    }

}

private object AnalysisBuilder {

  case class GameIsGone(id: String) extends lila.base.LilaException {
    val message = s"Analysis $id game is gone?!"
  }
}
