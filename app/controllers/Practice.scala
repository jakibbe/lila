package controllers

import play.api.libs.json._
import play.api.mvc._

import lila.api.Context
import lila.app._
import lila.practice.JsonView._
import lila.practice.UserStudy
import lila.study.Study.WithChapter
import lila.study.{ Chapter, Order, Study => StudyModel }
import lila.tree.Node.partitionTreeJsonWriter
import views._

object Practice extends LilaController {

  private def env = Env.practice
  private def studyEnv = Env.study

  def index = Open { implicit ctx =>
    env.api.get(ctx.me) flatMap { up =>
      Ok(html.practice.index(up)).fuccess
    }
  }

  def show(sectionId: String, studySlug: String, studyId: String) = Open { implicit ctx =>
    OptionFuResult(env.api.getStudyWithFirstOngoingChapter(ctx.me, studyId)) { us =>
      analysisJson(us) map {
        case (analysisJson, studyJson) => Ok(html.practice.show(us, lila.practice.JsonView.JsData(
          study = studyJson,
          analysis = analysisJson,
          practice = lila.practice.JsonView(us))))
      }
    } map NoCache
  }

  def chapter(studyId: String, chapterId: String) = Open { implicit ctx =>
    OptionFuResult(env.api.getStudyWithChapter(ctx.me, studyId, chapterId)) { us =>
      analysisJson(us) map {
        case (analysisJson, studyJson) => Ok(Json.obj(
          "study" -> studyJson,
          "analysis" -> analysisJson)) as JSON
      }
    } map NoCache
  }

  private def analysisJson(us: UserStudy)(implicit ctx: Context): Fu[(JsObject, JsObject)] = us match {
    case UserStudy(_, _, chapters, WithChapter(study, chapter)) =>
      val pov = UserAnalysis.makePov(chapter.root.fen.value.some, chapter.setup.variant)
      Env.round.jsonView.userAnalysisJson(pov, ctx.pref, chapter.setup.orientation, owner = false) zip
        studyEnv.jsonView(study, chapters, chapter, ctx.me) map {
          case (baseData, studyJson) =>
            val analysis = baseData ++ Json.obj(
              "treeParts" -> partitionTreeJsonWriter.writes {
                lila.study.TreeBuilder(chapter.root.withoutChildren, chapter.setup.variant)
              },
              "practiceGoal" -> lila.practice.PracticeGoal(chapter))
            (analysis, studyJson)
        }
  }

  def complete(chapterId: String, nbMoves: Int) = Auth { implicit ctx => me =>
    env.api.progress.setNbMoves(me, chapterId, lila.practice.PracticeProgress.NbMoves(nbMoves))
  }

  def config = Auth { implicit ctx => me =>
    for {
      struct <- env.api.structure.get
      form <- env.api.config.form
    } yield Ok(html.practice.config(struct, form))
  }

  def configSave = SecureBody(_.StreamConfig) { implicit ctx => me =>
    implicit val req = ctx.body
    env.api.config.form.flatMap { form =>
      FormFuResult(form) { err =>
        env.api.structure.get map { html.practice.config(_, err) }
      } { text =>
        env.api.config.set(text).valueOr(_ => funit) >>
          env.api.structure.clear >>
          Env.mod.logApi.practiceConfig(me.id) inject Redirect(routes.Practice.config)
      }
    }
  }

  private implicit def makeStudyId(id: String): StudyModel.Id = StudyModel.Id(id)
  private implicit def makeChapterId(id: String): Chapter.Id = Chapter.Id(id)
}