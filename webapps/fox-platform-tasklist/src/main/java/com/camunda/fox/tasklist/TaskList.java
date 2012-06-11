package com.camunda.fox.tasklist;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.PostConstruct;
import javax.ejb.EJB;
import javax.enterprise.event.Observes;
import javax.faces.bean.ViewScoped;
import javax.faces.context.FacesContext;
import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;

import org.activiti.engine.FormService;
import org.activiti.engine.ProcessEngine;
import org.activiti.engine.RepositoryService;
import org.activiti.engine.TaskService;
import org.activiti.engine.form.TaskFormData;
import org.activiti.engine.repository.ProcessDefinition;
import org.activiti.engine.task.Task;
import org.activiti.engine.task.TaskQuery;

import com.camunda.fox.client.impl.ProcessArchiveSupport;
import com.camunda.fox.platform.api.ProcessArchiveService;
import com.camunda.fox.platform.spi.ProcessArchive;
import com.camunda.fox.tasklist.event.TaskNavigationLinkSelectedEvent;
import com.camunda.fox.tasklist.identity.TasklistIdentityService;
import com.camunda.fox.tasklist.identity.User;

@ViewScoped
@Named
public class TaskList implements Serializable {

  private static final long serialVersionUID = 1L;
  private final static Logger log = Logger.getLogger(TaskList.class.getCanonicalName());
  private static final String TASK_LIST_OUTCOME = "taskList.jsf";

  @Inject
  private TaskService taskService;
  
  @Inject
  private RepositoryService repositoryService;  

  @Inject
  private FormService formService;

  @EJB(lookup = ProcessArchiveSupport.PROCESS_ARCHIVE_SERVICE_NAME)
  private ProcessArchiveService processArchiveService;

  @Inject
  private ProcessEngine processEngine;

  @Inject
  private Identity currentIdentity;

  @Inject
  private TasklistIdentityService identityService;

  private List<Task> tasks;

  private List<Task> myTasks;
  private List<Task> unassignedTasks;
  private Map<String, List<Task>> groupTasksMap;
  private Map<String, List<Task>> colleaguesTasksMap;

  private String delegateToColleague = "";

  @PostConstruct
  protected void init() {
    log.finest("initializing " + this.getClass().getSimpleName() + " (" + this + ")");
    tasks = getMyTasks();
  }

  public List<User> getColleages() {
    return identityService.getColleaguesByUserId(currentIdentity.getCurrentUser().getUsername());
  }

  public List<Task> getTasks() {
    return tasks;
  }

  public List<Task> getMyTasks() {
    if (myTasks == null) {
      myTasks = getList(taskService.createTaskQuery().taskAssignee(currentIdentity.getCurrentUser().getUsername()));
    }
    return myTasks;
  }
  
  public ProcessDefinition getProcessDefinition(String processDefinitionId) {
    // TODO: For performance improvements we could introduce our own DTO which queries the process definition together with the tasks immediately
    // see https://app.camunda.com/confluence/display/foxUserGuide/Performance+Tuning+with+custom+Queries
    return repositoryService.createProcessDefinitionQuery().processDefinitionId(processDefinitionId).singleResult();
  }  

  private List<Task> getList(TaskQuery taskQuery) {
    return taskQuery.orderByTaskCreateTime().desc().list();
  }

  public List<Task> getUnassignedTasks() {
    if (unassignedTasks == null) {
      unassignedTasks = getList(taskService.createTaskQuery().taskCandidateUser(currentIdentity.getCurrentUser().getUsername()));
    }
    return unassignedTasks;
  }

  public List<Task> getGroupTasks(String groupId) {
    if (groupTasksMap == null) {
      groupTasksMap = new HashMap<String, List<Task>>();
    }
    if (groupTasksMap.get(groupId) == null) {
      groupTasksMap.put(groupId, getList(taskService.createTaskQuery().taskCandidateGroup(groupId)));
    }
    return groupTasksMap.get(groupId);
  }

  public List<Task> getCoolleaguesTasks(String colleagueId) {
    if (colleaguesTasksMap == null) {
      colleaguesTasksMap = new HashMap<String, List<Task>>();
    }
    if (colleaguesTasksMap.get(colleagueId) == null) {
      colleaguesTasksMap.put(colleagueId, getList(taskService.createTaskQuery().taskAssignee(colleagueId)));
    }
    return colleaguesTasksMap.get(colleagueId);
  }

  public String getTaskFormUrl(Task task) {
    try {
      String formKey, taskFormUrl = "";
      TaskFormData taskFormData = formService.getTaskFormData(task.getId());
      if (taskFormData == null || taskFormData.getFormKey() == null) {
        return null;
      }
      formKey = taskFormData.getFormKey();
      ProcessArchive processArchive = processArchiveService.getProcessArchiveByProcessDefinitionId(task.getProcessDefinitionId(), processEngine.getName());
      String contextPath = (String) processArchive.getProperties().get(ProcessArchive.PROP_SERVLET_CONTEXT_PATH);
      String callbackUrl = getRequestURL();
      taskFormUrl = "../.." + contextPath + "/" + formKey + ".jsf?taskId=" + task.getId() + "&callbackUrl=" + callbackUrl;
      return taskFormUrl;
    }
    catch (Exception ex) {
      log.log(Level.WARNING, "Could not resolve task form URL for " + task, ex);
      return null;
    }
  }

  public String delegate(Task task) {
    if (delegateToColleague != null && delegateToColleague.length() > 0) {
      taskService.setAssignee(task.getId(), delegateToColleague);
    }
    return TASK_LIST_OUTCOME;
  }

  public boolean isPersonalTask(Task task) {
    String assignee = task.getAssignee();
    if (assignee == null) {
      return false;
    }
    return assignee.equals(currentIdentity.getCurrentUser().getUsername());
  }

  public String claimTask(Task task) {
    taskService.delegateTask(task.getId(), currentIdentity.getCurrentUser().getUsername());
    return TASK_LIST_OUTCOME;
  }

  private String getRequestURL() {
    Object request = FacesContext.getCurrentInstance().getExternalContext().getRequest();
    if (request instanceof HttpServletRequest) {
      return ((HttpServletRequest) request).getRequestURL().toString();
    } else {
      return "";
    }
  }

  public void linkSelected(@Observes TaskNavigationLinkSelectedEvent taskNavigationLinkSelectedEvent) {
    TaskNavigationLink link = taskNavigationLinkSelectedEvent.getLink();
    if (link instanceof MyTasksLink) {
      tasks = getMyTasks();
    } else if (link instanceof UnassignedTasksLink) {
      tasks = getUnassignedTasks();
    } else if (link instanceof GroupTasksLink) {
      tasks = getGroupTasks(((GroupTasksLink) link).getGroupId());
    } else if (link instanceof ColleaguesTasksLink) {
      tasks = getCoolleaguesTasks(((ColleaguesTasksLink) link).getColleagueId());
    }
  }

  public String getDelegateToColleague() {
    return delegateToColleague;
  }

  public void setDelegateToColleague(String delegateToColleague) {
    this.delegateToColleague = delegateToColleague;
  }

}
