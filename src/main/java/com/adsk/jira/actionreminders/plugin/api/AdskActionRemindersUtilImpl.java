/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.adsk.jira.actionreminders.plugin.api;

import com.atlassian.jira.bc.issue.search.SearchService;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.search.SearchException;
import com.atlassian.jira.issue.search.SearchResults;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.web.bean.PagerFilter;
import com.atlassian.jira.workflow.IssueWorkflowManager;
import com.opensymphony.workflow.loader.ActionDescriptor;
import java.util.Collection;
import java.util.List;
import org.apache.log4j.Logger;
import com.atlassian.jira.bc.issue.IssueService;
import com.atlassian.jira.config.ConstantsManager;
import com.atlassian.jira.config.properties.APKeys;
import com.atlassian.jira.config.properties.ApplicationProperties;
import com.atlassian.jira.issue.IssueInputParameters;
import com.atlassian.jira.issue.resolution.Resolution;
import com.atlassian.jira.issue.watchers.WatcherManager;
import com.atlassian.jira.mail.Email;
import com.atlassian.jira.security.groups.GroupManager;
import com.atlassian.jira.security.roles.ProjectRole;
import com.atlassian.jira.security.roles.ProjectRoleActors;
import com.atlassian.jira.security.roles.ProjectRoleManager;
import com.atlassian.jira.user.util.UserManager;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.mail.MailException;
import com.atlassian.mail.server.SMTPMailServer;
import java.text.MessageFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import org.quartz.CronExpression;


/**
 *
 * @author prasadve
 */
public final class AdskActionRemindersUtilImpl implements AdskActionRemindersUtil {
    private static final Logger logger = Logger.getLogger(AdskActionRemindersUtilImpl.class);
    
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");
    private static String defaultResolution = "1";
    private final ProjectManager projectManager = ComponentAccessor.getProjectManager();
    private final IssueService issueService = ComponentAccessor.getIssueService();
    private final ConstantsManager constantsManager = ComponentAccessor.getConstantsManager();
    private final ProjectRoleManager projectRoleManager = ComponentAccessor.getComponentOfType(ProjectRoleManager.class);
    private final WorkflowManager workflowManager = ComponentAccessor.getWorkflowManager();
    private final IssueWorkflowManager issueWorkflowManager = ComponentAccessor.getComponentOfType(IssueWorkflowManager.class);    
    private final SMTPMailServer mailServer = ComponentAccessor.getMailServerManager().getDefaultSMTPMailServer();
    private final ApplicationProperties properties = ComponentAccessor.getApplicationProperties();
    private final UserManager userManager = ComponentAccessor.getUserManager();
    private final GroupManager groupManager = ComponentAccessor.getGroupManager();
    private final WatcherManager watcherManager = ComponentAccessor.getWatcherManager();
    private final String BaseUrl = properties.getString(APKeys.JIRA_BASEURL); //"jira.baseurl"   
    private final SearchService searchService = ComponentAccessor.getComponent(SearchService.class);
    
    private final ActionRemindersAOMgr actionRemindersAOMgr;
    public AdskActionRemindersUtilImpl(ActionRemindersAOMgr actionRemindersAOMgr) {
        this.actionRemindersAOMgr = actionRemindersAOMgr;
        defaultResolution = getResolutionId();
    }
    
    public String getDateString(Date datetime) {
        return DATE_FORMAT.format(datetime); // example: 2011-05-26
    }
    
    public List<Project> getProjects() {        
       return projectManager.getProjects();
    }
    
    /**
     * Method being triggered by Scheduler
     * @param last_run_datetime
     * @param next_run_datetime 
     * Status: Active
     */
    public void run(Date last_run_datetime, Date next_run_datetime) {
        long startTime = System.currentTimeMillis();
        logger.debug("######## Action Reminder Scheduler - START ##########");        
        
        boolean enableRemindersStatus = getRemindersStatus();
        boolean enableActionsStatus = getActionsStatus();
        
        for(ActionRemindersAO action : actionRemindersAOMgr.getActiveActionReminders()) {
            if(isValidCronExp(action.getCronSchedule())) {
                Date nextValidTimeAfter = getNextValidTimeAfter(action.getCronSchedule(), last_run_datetime);
                logger.debug("*** Processing Action Reminder Config Id #"+ action.getID());
                logger.debug("Last Service Run Date:: "+ last_run_datetime.toString());
                logger.debug("Next Service Run Date:: "+ next_run_datetime.toString());
                logger.debug("Next Valid Action Reminder Run Date:: "+ nextValidTimeAfter.toString());
                
                if(nextValidTimeAfter.before(next_run_datetime)) {
                    logger.debug("* Cron schedule Valid - running");
                    process(action, enableRemindersStatus, enableActionsStatus);
                }else{
                    logger.debug("Skipping Action Reminder Config Id #"+ action.getID());
                }
            }
        }
                
        long totalTime = System.currentTimeMillis() - startTime;
        logger.debug("######## Action Reminder Scheduler - END. Took "+ totalTime/ 1000d +" Seconds ##########");
    }
    
    public Date getNextValidTimeAfter(String cronExp, Date currentDate) {
        Date date = null;
        try {
            CronExpression exp = new CronExpression(cronExp);
            date = exp.getNextValidTimeAfter(currentDate);
        } catch (ParseException e) {
            logger.error(e);
        }
        return date;
    }
    
    public boolean isValidCronExp(String cronExp) {
        return CronExpression.isValidExpression(cronExp);
    }
    
    public boolean getRemindersStatus() {
        boolean enableRemindersStatus = false;
        try {
            enableRemindersStatus = Boolean.parseBoolean(properties.getString(ENABLE_REMINDERS));
        }catch(ClassCastException e) {
            enableRemindersStatus = true;
            properties.setString(ENABLE_REMINDERS, ""+enableRemindersStatus);            
        }
        return enableRemindersStatus;
    }
    
    public boolean getActionsStatus() {
        boolean enableActionsStatus = false;
        try {
            enableActionsStatus = Boolean.parseBoolean(properties.getString(ENABLE_ACTIONS));
        }catch(ClassCastException e) {
            enableActionsStatus = true;
            properties.setString(ENABLE_ACTIONS, ""+enableActionsStatus);
        }
        return enableActionsStatus;
    }
    
    public void process(ActionRemindersAO map, boolean reminders, boolean actions) {
        if(reminders == false && actions == false) {
            logger.debug(map.getID()+" - Both reminders and actions are set false. Skipping!");
            return;
        }
        
        boolean is_issue_action = false;
        if(actions == true && map.getIssueAction() != null && !"".equals(map.getIssueAction())) {
            is_issue_action = true;
        }else if(reminders == false && is_issue_action == false){
            logger.debug(map.getID()+" - Both reminders and actions are set false. Skipping!");
            return;
        }             
        
        ApplicationUser runAppUser = userManager.getUserByName(map.getRunAuthor());
        if(runAppUser == null){
            logger.debug(map.getRunAuthor()+" - Run Author is Null/not exists!");
            return;
        }
        
        Project projectObj = projectManager.getProjectObjByKey(map.getProjectKey());
        if(projectObj == null){
            logger.debug(map.getProjectKey()+" - Project is Null/not exists!");
            return;
        }
        
        
        long startTime = System.currentTimeMillis();
        
        try {
            String secureQuery = MessageFormat.format("project = {0} AND {1}", projectObj.getKey(), map.getQuery());
            
            SearchService.ParseResult parseResult =  searchService.parseQuery(runAppUser, secureQuery);
            
            if (parseResult.isValid()) {
                logger.debug("Processing secure query -> "+ parseResult.getQuery());
                
                SearchResults searchResults = searchService.search(runAppUser, parseResult.getQuery(), PagerFilter.newPageAlignedFilter(0, 1000));
                
                for(Issue issue : searchResults.getIssues()) {
                    logger.debug("processing issue -> "+ issue.getKey());
                                        
                    if(is_issue_action == true) { // Transition Action                        
                        logger.debug("processing transition action -> "+ map.getIssueAction());                                                
                        
                        Collection<ActionDescriptor> ActionDescriptors = workflowManager.getWorkflow(issue).getActionsByName(map.getIssueAction());
                        boolean is_action_exists = false;
                        
                        for(ActionDescriptor actionDescriptor : ActionDescriptors) {                            
                            if(issueWorkflowManager.isValidAction(issue, actionDescriptor.getId(), runAppUser)) {
                                logger.debug("action is valid - "+ actionDescriptor.getName() +" : "+ actionDescriptor.getId()); 
                                is_action_exists = true;
                                IssueInputParameters issueInputParameters = issueService.newIssueInputParameters();
                                issueInputParameters.setRetainExistingValuesWhenParameterNotProvided(true);                                                                
                                issueInputParameters.setResolutionId(defaultResolution);
                                issueInputParameters.setComment(map.getMessage());

                                IssueService.TransitionValidationResult validation = issueService.validateTransition(runAppUser, issue.getId(), 
                                        actionDescriptor.getId(), issueInputParameters);                                                                        

                                if (validation.isValid()) {
                                    IssueService.IssueResult issueResult = issueService.transition(runAppUser, validation);
                                    if (issueResult.isValid()) {
                                        logger.debug("Transition successful.");
                                        for(String e : issueResult.getErrorCollection().getErrorMessages()) {
                                            logger.debug(e);
                                        }
                                    }
                                } else {
                                    logger.debug("Transition validation errors: ");
                                    for(String e : validation.getErrorCollection().getErrorMessages()) {
                                        logger.debug(e);
                                    }
                                }
                                break;
                            }                            
                        }
                        
                        if( is_action_exists == false ) {    
                            logger.debug("Transition action is not valid - "+ map.getIssueAction());
                        }
                    
                    } else {
                        
                        logger.debug("Execution count is 0 so sending now.");
                        if( reminders ) { // Remind or re-notify
                            sendReminders(map, issue, runAppUser);
                        }
                    }
                    
                    actionRemindersAOMgr.setActionRemindersLastRun(map.getID()); // set last run
                }
            }
        }
        catch(SearchException e) {
            logger.error(e.getLocalizedMessage());
        }
        
        long totalTime = System.currentTimeMillis() - startTime;
        logger.debug("Action Reminder Finished. Took "+ totalTime/ 1000d +" Seconds");
    }
    
    public String getResolutionId() {
        Collection<Resolution> resolutions = constantsManager.getResolutions();
        for(Resolution r : resolutions){
            return r.getId();
        }
        return "1";
    }
    
    public void sendReminders(ActionRemindersAO map, Issue issue, ApplicationUser runUser) {
        String subject = MessageFormat.format("({0}) {1}", issue.getKey(), issue.getSummary());
        String issueLink = MessageFormat.format("{0}/browse/{1}", BaseUrl, issue.getKey());
        String body = MessageFormat.format("{0}\n\n{1}", map.getMessage(), issueLink);        
        String ccfrom = runUser != null ? runUser.getEmailAddress() : "";
        Set<String> emailAddrs = new HashSet<String>();
        
        if(map.getNotifyAssignee()) {
            if(issue.getAssigneeUser() != null) {
                emailAddrs.add(issue.getAssigneeUser().getEmailAddress());
            }
        }
        
        if(map.getNotifyReporter()) {
            if(issue.getReporterUser() != null) {
                emailAddrs.add(issue.getReporterUser().getEmailAddress());
            }
        }
        
        if(map.getNotifyWatchers()) {
            emailAddrs.addAll(getWatchersUsers(issue));
        }
        
        if(map.getNotifyProjectrole() != null && !"".equals(map.getNotifyProjectrole())) {
            emailAddrs.addAll(getRoleUsers(map.getProjectKey(), map.getNotifyProjectrole()));
        }
        
        if(map.getNotifyGroup()!= null && !"".equals(map.getNotifyGroup())) {
            emailAddrs.addAll(getGroupUsers(map.getNotifyGroup()));
        }
        
        logger.debug("Total email users size - "+ emailAddrs.size());
        
        for(String email : emailAddrs) {
            sendMail(email, subject, body, ccfrom);
        }
    }
    
    public Set<String> getGroupUsers(String group) {
        Set<String> users = new HashSet<String>();
        if(!"jira-administrators".equalsIgnoreCase(group) && !"jira-developers".equalsIgnoreCase(group) && !"jira-users".equalsIgnoreCase(group)) {
            Collection<ApplicationUser> groupUsers = groupManager.getUsersInGroup(group);
            logger.debug("Group users size - "+ group +" : "+ groupUsers.size());
            for(ApplicationUser au : groupUsers){
                users.add(au.getEmailAddress());
            }
        }else{
            logger.warn(group +" - Default jira groups are not supported!");
        }
        return users;
    }
    
    public ProjectRole getProjectRole(String projectRole) {
        Collection<ProjectRole> projectRoles = projectRoleManager.getProjectRoles();
        for(ProjectRole role : projectRoles){
            if(role.getName().equalsIgnoreCase(projectRole)){
                return role;
            }
        }
        return null;
    }
    
    public Set<String> getRoleUsers(String projectKey, String projectRole) {                         
        Set<String> users = new HashSet<String>();
        if(!"ADMINISTRATORS".equalsIgnoreCase(projectRole) && !"DEVELOPERS".equalsIgnoreCase(projectRole) && !"USERS".equalsIgnoreCase(projectRole)) {
            Project projectObject = projectManager.getProjectObjByKey(projectKey);
            ProjectRole devRole = getProjectRole(projectRole);
            if(devRole != null) {
                logger.debug("Project role name: "+ devRole.getName());
                ProjectRoleActors roleActors = projectRoleManager.getProjectRoleActors(devRole, projectObject);
                Set<ApplicationUser> actors = roleActors.getApplicationUsers();
                logger.debug("Project role users size - "+ projectRole +" : "+ actors.size());
                for(ApplicationUser au : actors){
                    users.add(au.getEmailAddress());
                }
            }else{
                logger.debug("Project role is not exists! "+ projectRole);
            }
        }else{
            logger.warn(projectKey +":"+ projectRole +" - Default project role does not supported!");
        }
        return users;
    }
    
    public Set<String> getWatchersUsers(Issue issue) {
        Set<String> users = new HashSet<String>();
        List<String> watchUsers = watcherManager.getCurrentWatcherUsernames(issue);
        logger.debug("Issue watchers size - "+ issue.getKey() +" : "+ watchUsers.size());
        for(String user : watchUsers) {
            ApplicationUser au = userManager.getUserByName(user);
            if(au != null) {
                users.add(au.getEmailAddress());
            }
        }
        return users;
    }
    
    public void sendMail(String emailAddr, String subject, String body, String ccfrom) {
        try {            
            if (mailServer != null) {
                Email email = new Email(emailAddr);
                email.setSubject(subject);
                email.setBody(body);
                email.setFrom(ccfrom);
                email.setCc(ccfrom);                
                mailServer.send(email);
                logger.debug("Mail sent To: "+ emailAddr +" Cc: "+ ccfrom);
            } else {
                logger.warn("Please make sure that a valid mailServer is configured");
            }
        } catch (MailException ex) {
            logger.error(ex.getLocalizedMessage());
        }
    }
    
}