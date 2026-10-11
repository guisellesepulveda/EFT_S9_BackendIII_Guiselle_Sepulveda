package cl.bancoxyz.batch.common;

import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

/** Step final: emite el reporte de ejecución del job. */
public class ExecutionReportTasklet implements Tasklet {

    private final ExecutionReporter reporter;

    public ExecutionReportTasklet(ExecutionReporter reporter) {
        this.reporter = reporter;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        var stepExecution = chunkContext.getStepContext().getStepExecution();
        reporter.report(stepExecution.getJobExecution(), stepExecution.getStepName());
        return RepeatStatus.FINISHED;
    }
}
