package com.supportforge.persistence.port;

import com.supportforge.evaluation.EvalReport;

import java.util.List;

/** 端到端评测报告的持久化端口。 */
public interface EvaluationReportPersistencePort {

    default String scopeKey() {
        return "default";
    }

    List<EvalReport> load();

    void save(List<EvalReport> reports);

    List<EvalReport> keepLatest(List<EvalReport> reports);
}
