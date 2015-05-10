package com.nitorcreations.nflow.engine.internal.workflow;

import static org.springframework.util.Assert.notNull;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import org.joda.time.DateTime;

import com.nitorcreations.nflow.engine.internal.dao.WorkflowInstanceDao;
import com.nitorcreations.nflow.engine.workflow.definition.StateExecution;
import com.nitorcreations.nflow.engine.workflow.definition.WorkflowState;
import com.nitorcreations.nflow.engine.workflow.instance.QueryWorkflowInstances;
import com.nitorcreations.nflow.engine.workflow.instance.WorkflowInstance;

public class StateExecutionImpl implements StateExecution {

  private final WorkflowInstance instance;
  private final ObjectStringMapper objectMapper;
  private final WorkflowInstanceDao workflowDao;
  private final WorkflowInstancePreProcessor workflowInstancePreProcessor;
  private DateTime nextActivation;
  private String nextState;
  private String nextStateReason;
  private boolean isRetry;
  private Throwable thrown;
  private boolean isFailed;
  private boolean isRetryCountExceeded;
  private boolean wakeUpParentWorkflow = false;
  private final List<WorkflowInstance> newChildWorkflows = new LinkedList<>();

  public StateExecutionImpl(WorkflowInstance instance, ObjectStringMapper objectMapper, WorkflowInstanceDao workflowDao,
                            WorkflowInstancePreProcessor workflowInstancePreProcessor) {
    this.instance = instance;
    this.objectMapper = objectMapper;
    this.workflowDao = workflowDao;
    this.workflowInstancePreProcessor = workflowInstancePreProcessor;
  }

  public DateTime getNextActivation() {
    return this.nextActivation;
  }

  public String getNextState() {
    return this.nextState;
  }

  public String getNextStateReason() {
    return this.nextStateReason;
  }

  public String getCurrentStateName() {
    return instance.state;
  }

  @Override
  public int getWorkflowInstanceId() {
    return instance.id;
  }

  @Override
  public String getWorkflowInstanceExternalId() {
    return instance.externalId;
  }

  @Override
  public String getBusinessKey() {
    return instance.businessKey;
  }

  @Override
  public int getRetries() {
    return instance.retries;
  }

  @Override
  public String getVariable(String name) {
    return getVariable(name, (String) null);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T getVariable(String name, Class<T> type) {
    return (T) objectMapper.convertToObject(type, name, getVariable(name));
  }

  @Override
  public String getVariable(String name, String defaultValue) {
    if (instance.stateVariables.containsKey(name)) {
      return instance.stateVariables.get(name);
    }
    return defaultValue;
  }

  @Override
  public void setVariable(String name, String value) {
    instance.stateVariables.put(name, value);
  }

  @Override
  public void setVariable(String name, Object value) {
    setVariable(name, objectMapper.convertFromObject(name, value));
  }

  public void setNextActivation(DateTime activation) {
    this.nextActivation = activation;
  }

  public void setNextState(WorkflowState state) {
    notNull(state, "Next state can not be null");
    this.nextState = state.name();
  }

  public void setNextStateReason(String reason) {
    this.nextStateReason = reason;
  }

  public boolean isRetry() {
    return isRetry;
  }

  public void setRetry(boolean isRetry) {
    this.isRetry = isRetry;
  }

  public boolean isFailed() {
    return isFailed;
  }

  public Throwable getThrown() {
    return thrown;
  }

  public void setFailed() {
    isFailed = true;
  }

  public void setFailed(Throwable t) {
    isFailed = true;
    thrown = t;
  }

  public boolean isRetryCountExceeded() {
    return isRetryCountExceeded;
  }

  public void setRetryCountExceeded() {
    isRetryCountExceeded = true;
  }

  @Override
  public void addChildWorkflows(WorkflowInstance ... childWorkflows) {
    notNull(childWorkflows, "childWorkflows can not be null");
    for(WorkflowInstance child : childWorkflows) {
      notNull(child, "childWorkflow can not be null");
      WorkflowInstance processedChild = workflowInstancePreProcessor.process(child);
      newChildWorkflows.add(processedChild);
    }
  }

  public List<WorkflowInstance> getNewChildWorkflows() {
    return Collections.unmodifiableList(newChildWorkflows);
  }

  @Override
  public List<WorkflowInstance> queryChildWorkflows(QueryWorkflowInstances query) {
    QueryWorkflowInstances restrictedQuery = new QueryWorkflowInstances.Builder(query)
            .setParentWorkflowId(instance.id).build();
    return workflowDao.queryWorkflowInstances(restrictedQuery);
  }

  @Override
  public void wakeUpParentWorkflow() {
    wakeUpParentWorkflow = true;
  }

  public boolean isWakeUpParentWorkflowSet() {
    return wakeUpParentWorkflow;
  }
}
