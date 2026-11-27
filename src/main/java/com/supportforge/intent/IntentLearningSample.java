package com.supportforge.intent;


public record IntentLearningSample(
        String message,
        String intent
) {


    public IntentLearningSample {

        if (message == null
                || message.isBlank()) {

            throw new IllegalArgumentException(
                    "意图学习样本消息不能为空"
            );
        }


        if (intent == null
                || intent.isBlank()) {

            throw new IllegalArgumentException(
                    "意图学习样本标签不能为空"
            );
        }

        message = message.strip();


        IntentCategory category =
                IntentCategory.fromValue(
                        intent.strip()
                );


        if (category == IntentCategory.OTHER) {
            throw new IllegalArgumentException(
                    "意图学习样本不能使用未知标签或other："
                            + intent
            );
        }


        intent = category.value();
    }


    public IntentLearningSample(
            String message,
            IntentCategory intent
    ) {
        this(
                message,
                intent == null
                        ? null
                        : intent.value()
        );
    }

    /**
     * 将持久化字符串恢复成业务枚举，例如"refund" -> REFUND；不读文件、不重新学习。
     * 构造时已经拒绝未知标签并存成统一value，因此正常创建的样本可以恢复对应的非OTHER枚举。
     */
    public IntentCategory category() {
        return IntentCategory.fromValue(
                intent
        );
    }
}
