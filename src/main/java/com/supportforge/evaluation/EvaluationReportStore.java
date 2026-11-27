package com.supportforge.evaluation;

import com.supportforge.persistence.port.EvaluationReportPersistencePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


@Component
@ConditionalOnProperty(
        prefix = "supportforge.persistence", name = "evaluation",
        havingValue = "json", matchIfMissing = true
)
public class EvaluationReportStore
        implements EvaluationReportPersistencePort {

    private final ObjectMapper objectMapper;
    private final Path reportPath;
    private final int maxHistory;


    public EvaluationReportStore(
            ObjectMapper objectMapper,

            @Value(
                    "${supportforge.evaluation.report-path:"
                            + "data/evaluation-reports.json}"
            )
            String reportPath,

            @Value(
                    "${supportforge.evaluation.max-history:50}"
            )
            int maxHistory
    ) {


        if (maxHistory < 1) {
            throw new IllegalArgumentException(
                    "评测报告最大保留数量必须大于0"
            );
        }

        this.objectMapper = objectMapper;


        this.reportPath = Path.of(reportPath)
                .toAbsolutePath()
                .normalize();
        this.maxHistory = maxHistory;
    }


    public List<EvalReport> load() {
        try {


            if (Files.notExists(reportPath)
                    || Files.size(reportPath) == 0) {
                return List.of();
            }


            EvalReport[] reports =
                    objectMapper.readValue(
                            reportPath,
                            EvalReport[].class
                    );


            if (reports == null) {
                return List.of();
            }


            return keepLatest(
                    Arrays.asList(reports)
            );
        } catch (IOException | RuntimeException exception) {


            throw new IllegalStateException(
                    "读取评测报告失败：" + reportPath,
                    exception
            );
        }
    }


    public synchronized void save(
            List<EvalReport> reports
    ) {


        List<EvalReport> limitedReports =
                keepLatest(reports);

        try {
            Path parent = reportPath.getParent();

            if (parent != null) {

                Files.createDirectories(parent);
            }


            Path temporaryPath =
                    reportPath.resolveSibling(
                            reportPath.getFileName()
                                    + ".tmp"
                    );


            objectMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(
                            temporaryPath,
                            limitedReports
                    );

            try {


                Files.move(
                        temporaryPath,
                        reportPath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (
                    AtomicMoveNotSupportedException exception
            ) {


                Files.move(
                        temporaryPath,
                        reportPath,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } catch (IOException | RuntimeException exception) {


            throw new IllegalStateException(
                    "保存评测报告失败：" + reportPath,
                    exception
            );
        }
    }


    public List<EvalReport> keepLatest(
            List<EvalReport> reports
    ) {

        if (reports == null || reports.isEmpty()) {
            return List.of();
        }


        List<EvalReport> snapshot =
                new ArrayList<>(reports);


        int fromIndex =
                Math.max(
                        0,
                        snapshot.size() - maxHistory
                );


        return List.copyOf(
                snapshot.subList(
                        fromIndex,
                        snapshot.size()
                )
        );
    }


    public int maxHistory() {
        return maxHistory;
    }


    public Path reportPath() {
        return reportPath;
    }
}
