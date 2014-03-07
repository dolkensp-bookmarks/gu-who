package controllers

import play.api._
import play.api.mvc._
import org.kohsuke.github._
import collection.convert.wrapAll._
import akka.actor.Actor
import lib.{StateUpdate, AccountRequirement, AccountRequirements, Organisation}

trait GithubClient {
  val conn = GitHub.connect();
}

object Application extends Controller {

  def index = Action {
    BotScript.run()
    Ok
  }
}


object BotScript {
  def run() = {

    val users = Organisation.testUsers
    val openIssues = Bot.openIssues

    val openIssuesByUser = (for(i<-openIssues) yield i.getAssignee -> i).toMap

    val problemsByUser = (for {
        u <- users;
        problems = AccountRequirements.failedBy(u);
        if(!problems.isEmpty)
    } yield u -> problems).toMap

    val usersWithIssuesToCreate = problemsByUser.keySet.diff(openIssuesByUser.keySet)

    for(user <- usersWithIssuesToCreate) {
      Organisation.checkAllTeamMembership(user)
      println("creating issue for user " + user)
      Issue.create(user, problemsByUser(user))
    }

    for {
      i<-openIssues;
      user <- Option(i.getAssignee)
      problems <- problemsByUser.get(user)
    } {
      println("updating issue for user " + user + " and issue " + i)
      Issue.update(i, problems)
    }

  }
}

object Bot extends GithubClient {
  val bot = conn.getMyself
  lazy val openIssues = Organisation.peopleRepo.getIssues(GHIssueState.OPEN).toList.filter(_.getUser==bot)
}


object Issue {

  def create(user: GHUser, problems: Set[AccountRequirement]): Unit = {
    val contents = views.html.issue(user, problems).body

    for(title <- problems.map(_.title(user)).headOption) {
      val issue = Organisation.peopleRepo.createIssue(title)
      issue.body(contents)
      issue.assignee(user)
      for(p<-problems) { issue.label(p.issueLabel) }
      issue.create()
    }
  }

  def update(issue: GHIssue, currentProblems: Set[AccountRequirement]) {
    val stateUpdate = StateUpdate(issue, currentProblems)

    if (stateUpdate.isChange) {
      val unassociatedLabels = issue.getLabels.map(_.getName).filterNot(AccountRequirements.AllLabels)
      val newLabelSet = currentProblems.map(_.issueLabel) ++ unassociatedLabels
      issue.setLabels(newLabelSet.toSeq: _*)
    }

    if (stateUpdate.worthyOfComment) {
      issue.comment(views.html.update(stateUpdate).body)
    }

    if (stateUpdate.issueCanBeClosed) {
      issue.close()
    }
  }
}

class GitHubPoller extends Actor {
  def receive = {
    case RunScript => BotScript.run()
  }
}

case object AddTeamMembers

case object RunScript

