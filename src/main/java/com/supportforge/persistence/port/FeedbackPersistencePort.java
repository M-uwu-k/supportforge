package com.supportforge.persistence.port;

import com.supportforge.feedback.FeedbackSnapshot;
import com.supportforge.feedback.AnswerFeedback;
import com.supportforge.feedback.AnswerRecord;

/** 回答与用户反馈的持久化端口。 */
public interface FeedbackPersistencePort {

    /** 当前调用的数据隔离键；JSON使用default，PostgreSQL使用请求租户。 */
    default String scopeKey() {
        return "default";
    }

    FeedbackSnapshot load();

    void save(FeedbackSnapshot snapshot);

    /** PostgreSQL等实现可覆盖为单行事务，JSON默认仍原子保存完整快照。 */
    default void saveAnswer(FeedbackSnapshot snapshot, AnswerRecord answer) {
        save(snapshot);
    }

    /** PostgreSQL等实现可覆盖为单行事务，JSON默认仍原子保存完整快照。 */
    default void saveFeedback(FeedbackSnapshot snapshot, AnswerFeedback feedback) {
        save(snapshot);
    }

    /** true表示两个增量方法可并发执行，业务层无需把整个租户串行化。 */
    default boolean supportsConcurrentIncrementalWrites() {
        return false;
    }

    FeedbackSnapshot normalize(FeedbackSnapshot snapshot);
}
