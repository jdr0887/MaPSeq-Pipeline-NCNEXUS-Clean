package edu.unc.mapseq.workflow.ncnexus.clean;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.jgrapht.DirectedGraph;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.renci.jlrm.condor.CondorJob;
import org.renci.jlrm.condor.CondorJobBuilder;
import org.renci.jlrm.condor.CondorJobEdge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.unc.mapseq.dao.MaPSeqDAOBeanService;
import edu.unc.mapseq.dao.MaPSeqDAOException;
import edu.unc.mapseq.dao.model.Flowcell;
import edu.unc.mapseq.dao.model.Sample;
import edu.unc.mapseq.dao.model.Workflow;
import edu.unc.mapseq.dao.model.WorkflowRunAttempt;
import edu.unc.mapseq.module.core.RemoveCLI;
import edu.unc.mapseq.workflow.WorkflowException;
import edu.unc.mapseq.workflow.core.WorkflowJobFactory;
import edu.unc.mapseq.workflow.sequencing.AbstractSequencingWorkflow;
import edu.unc.mapseq.workflow.sequencing.SequencingWorkflowJobFactory;
import edu.unc.mapseq.workflow.sequencing.SequencingWorkflowUtil;

public class NCNEXUSCleanWorkflow extends AbstractSequencingWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(NCNEXUSCleanWorkflow.class);

    public NCNEXUSCleanWorkflow() {
        super();
    }

    @Override
    public Graph<CondorJob, CondorJobEdge> createGraph() throws WorkflowException {
        logger.info("ENTERING createGraph()");

        DirectedGraph<CondorJob, CondorJobEdge> graph = new DefaultDirectedGraph<CondorJob, CondorJobEdge>(CondorJobEdge.class);

        int count = 0;

        Set<Sample> sampleSet = SequencingWorkflowUtil.getAggregatedSamples(getWorkflowBeanService().getMaPSeqDAOBeanService(),
                getWorkflowRunAttempt());
        logger.info("sampleSet.size(): {}", sampleSet.size());
        WorkflowRunAttempt attempt = getWorkflowRunAttempt();

        String siteName = getWorkflowBeanService().getAttributes().get("siteName");
        String flowcellStagingDirectory = getWorkflowBeanService().getAttributes().get("flowcellStagingDirectory");

        Set<Flowcell> flowcells = new HashSet<Flowcell>();
        for (Sample sample : sampleSet) {
            if (!flowcells.contains(sample.getFlowcell())) {
                flowcells.add(sample.getFlowcell());
            }
        }
        Collections.synchronizedSet(flowcells);

        for (Flowcell flowcell : flowcells) {
            File flowcellStagingDir = new File(flowcellStagingDirectory, flowcell.getName());
            if (flowcellStagingDir.exists()) {
                CondorJobBuilder builder = WorkflowJobFactory.createJob(++count, RemoveCLI.class, attempt.getId()).siteName(siteName);
                builder.addArgument(RemoveCLI.FILE, flowcellStagingDir.getAbsolutePath());
            }

            Set<Integer> laneSet = new HashSet<>();
            sampleSet.forEach(a -> laneSet.add(a.getLaneIndex()));
            Collections.synchronizedSet(laneSet);

            File bclDir = new File(flowcell.getBaseDirectory());
            File bclFlowcellDir = new File(bclDir, flowcell.getName());

            for (Integer lane : laneSet) {
                File unalignedDir = new File(bclFlowcellDir, String.format("%s.%d", "Unaligned", lane.toString()));
                CondorJobBuilder builder = WorkflowJobFactory.createJob(++count, RemoveCLI.class, attempt.getId()).siteName(siteName);
                builder.addArgument(RemoveCLI.FILE, unalignedDir);
                CondorJob removeUnalignedDirectoryJob = builder.build();
                logger.info(removeUnalignedDirectoryJob.toString());
                graph.addVertex(removeUnalignedDirectoryJob);
            }

        }

        MaPSeqDAOBeanService daoBean = getWorkflowBeanService().getMaPSeqDAOBeanService();

        Workflow baselineWorkflow = null;
        try {
            List<Workflow> workflowList = daoBean.getWorkflowDAO().findByName("NCNEXUSBaseline");
            if (CollectionUtils.isNotEmpty(workflowList)) {
                baselineWorkflow = workflowList.get(0);
            }
        } catch (MaPSeqDAOException e1) {
            e1.printStackTrace();
        }

        for (Sample sample : sampleSet) {

            if ("Undetermined".equals(sample.getBarcode())) {
                continue;
            }

            logger.debug(sample.toString());

            List<File> readPairList = SequencingWorkflowUtil.getReadPairList(sample);
            logger.debug("readPairList.size(): {}", readPairList.size());

            if (readPairList.size() != 2) {
                throw new WorkflowException("readPairList != 2");
            }

            File r1FastqFile = readPairList.get(0);
            String r1FastqRootName = SequencingWorkflowUtil.getRootFastqName(r1FastqFile.getName());

            File r2FastqFile = readPairList.get(1);
            String r2FastqRootName = SequencingWorkflowUtil.getRootFastqName(r2FastqFile.getName());

            String fastqLaneRootName = StringUtils.removeEnd(r2FastqRootName, "_R2");

            try {

                File outputDirectory = SequencingWorkflowUtil.createOutputDirectory(sample, baselineWorkflow);

                File saiR1OutFile = new File(outputDirectory, r1FastqRootName + ".sai");

                File saiR2OutFile = new File(outputDirectory, r2FastqRootName + ".sai");

                File bwaSAMPairedEndOutFile = new File(outputDirectory, fastqLaneRootName + ".sam");

                File fixRGOutput = new File(outputDirectory, bwaSAMPairedEndOutFile.getName().replace(".sam", ".rg.bam"));

                File picardAddOrReplaceReadGroupsIndexOut = new File(outputDirectory, fixRGOutput.getName().replace(".bam", ".bai"));

                File picardMarkDuplicatesMetricsFile = new File(outputDirectory, fixRGOutput.getName().replace(".bam", ".md.metrics"));

                File picardMarkDuplicatesOutput = new File(outputDirectory, fixRGOutput.getName().replace(".bam", ".md.bam"));

                File picardMarkDuplicatesIndexOut = new File(outputDirectory, picardMarkDuplicatesOutput.getName().replace(".bam", ".bai"));

                File realignTargetCreatorOut = new File(outputDirectory, picardMarkDuplicatesOutput.getName().replace(".bam", ".targets.intervals"));

                File indelRealignerOut = new File(outputDirectory, picardMarkDuplicatesOutput.getName().replace(".bam", ".ir.bam"));

                File picardFixMateOutput = new File(outputDirectory, indelRealignerOut.getName().replace(".bam", ".fm.bam"));

                File picardFixMateIndexOut = new File(outputDirectory, picardFixMateOutput.getName().replace(".bam", ".bai"));

                // new job
                CondorJobBuilder builder = SequencingWorkflowJobFactory.createJob(++count, RemoveCLI.class, attempt.getId(), sample.getId())
                        .siteName(siteName);
                builder.addArgument(RemoveCLI.FILE, saiR1OutFile.getAbsolutePath()).addArgument(RemoveCLI.FILE, saiR2OutFile.getAbsolutePath())
                        .addArgument(RemoveCLI.FILE, bwaSAMPairedEndOutFile.getAbsolutePath())
                        .addArgument(RemoveCLI.FILE, picardMarkDuplicatesOutput.getAbsolutePath())
                        .addArgument(RemoveCLI.FILE, picardMarkDuplicatesIndexOut.getAbsolutePath())
                        .addArgument(RemoveCLI.FILE, indelRealignerOut.getAbsolutePath())
                        .addArgument(RemoveCLI.FILE, picardFixMateOutput.getAbsolutePath())
                        .addArgument(RemoveCLI.FILE, picardFixMateIndexOut.getAbsolutePath());

            } catch (Exception e) {
                throw new WorkflowException(e);
            }
        }

        return graph;
    }

}
