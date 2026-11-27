package com.supportforge.indexing;

/** 索引任务领取结果，避免把“已完成”和“暂时忙”都表达成空Optional。 */
public record KnowledgeJobClaim(
        Status status,
        KnowledgeIndexWork work
) {

    public KnowledgeJobClaim {
        if (status == null) {
            throw new IllegalArgumentException("任务领取状态不能为空");
        }
        if ((status == Status.ACQUIRED) != (work != null)) {
            throw new IllegalArgumentException("只有ACQUIRED状态必须携带工作单");
        }
    }

    public static KnowledgeJobClaim acquired(KnowledgeIndexWork work) {
        return new KnowledgeJobClaim(Status.ACQUIRED, work);
    }

    public static KnowledgeJobClaim of(Status status) {
        return new KnowledgeJobClaim(status, null);
    }

    public enum Status {
        ACQUIRED,
        ALREADY_SUCCEEDED,
        BUSY,
        EXHAUSTED,
        MISSING
    }
}
