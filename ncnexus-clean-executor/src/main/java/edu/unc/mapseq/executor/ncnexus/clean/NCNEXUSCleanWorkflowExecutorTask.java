package edu.unc.mapseq.executor.ncnexus.clean;

import java.util.Date;
import java.util.List;
import java.util.TimerTask;

import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.unc.mapseq.dao.MaPSeqDAOBeanService;
import edu.unc.mapseq.dao.MaPSeqDAOException;
import edu.unc.mapseq.dao.WorkflowDAO;
import edu.unc.mapseq.dao.WorkflowRunAttemptDAO;
import edu.unc.mapseq.dao.model.Workflow;
import edu.unc.mapseq.dao.model.WorkflowRunAttempt;
import edu.unc.mapseq.workflow.WorkflowBeanService;
import edu.unc.mapseq.workflow.WorkflowExecutor;
import edu.unc.mapseq.workflow.WorkflowTPE;
import edu.unc.mapseq.workflow.ncnexus.clean.NCNEXUSCleanWorkflow;

public class NCNEXUSCleanWorkflowExecutorTask extends TimerTask {

    private static final Logger logger = LoggerFactory.getLogger(NCNEXUSCleanWorkflowExecutorTask.class);

    private final WorkflowTPE threadPoolExecutor = new WorkflowTPE();

    private String workflowName;

    private WorkflowBeanService workflowBeanService;

    public NCNEXUSCleanWorkflowExecutorTask() {
        super();
    }

    @Override
    public void run() {
        logger.info("ENTERING run()");

        threadPoolExecutor.setCorePoolSize(workflowBeanService.getCorePoolSize());
        threadPoolExecutor.setMaximumPoolSize(workflowBeanService.getMaxPoolSize());

        logger.info(String.format("ActiveCount: %d, TaskCount: %d, CompletedTaskCount: %d",
                threadPoolExecutor.getActiveCount(), threadPoolExecutor.getTaskCount(),
                threadPoolExecutor.getCompletedTaskCount()));

        MaPSeqDAOBeanService mapseqDAOBeanService = this.workflowBeanService.getMaPSeqDAOBeanService();

        WorkflowDAO workflowDAO = mapseqDAOBeanService.getWorkflowDAO();
        WorkflowRunAttemptDAO workflowRunAttemptDAO = mapseqDAOBeanService.getWorkflowRunAttemptDAO();

        try {
            List<Workflow> workflowList = workflowDAO.findByName(getWorkflowName());

            if (CollectionUtils.isEmpty(workflowList)) {
                logger.error("No Workflow Found: {}", getWorkflowName());
                return;
            }

            List<WorkflowRunAttempt> attempts = workflowRunAttemptDAO.findEnqueued(workflowList.get(0).getId());

            if (CollectionUtils.isNotEmpty(attempts)) {

                logger.info("dequeuing {} WorkflowRunAttempt", attempts.size());
                for (WorkflowRunAttempt attempt : attempts) {

                    NCNEXUSCleanWorkflow cleanWorkflow = new NCNEXUSCleanWorkflow();
                    attempt.setVersion(cleanWorkflow.getVersion());
                    attempt.setDequeued(new Date());
                    workflowRunAttemptDAO.save(attempt);

                    cleanWorkflow.setWorkflowBeanService(workflowBeanService);
                    cleanWorkflow.setWorkflowRunAttempt(attempt);
                    threadPoolExecutor.submit(new WorkflowExecutor(cleanWorkflow));

                }

            }

        } catch (MaPSeqDAOException e) {
            e.printStackTrace();
        }

    }

    public WorkflowBeanService getWorkflowBeanService() {
        return workflowBeanService;
    }

    public void setWorkflowBeanService(WorkflowBeanService workflowBeanService) {
        this.workflowBeanService = workflowBeanService;
    }

    public String getWorkflowName() {
        return workflowName;
    }

    public void setWorkflowName(String workflowName) {
        this.workflowName = workflowName;
    }

}
