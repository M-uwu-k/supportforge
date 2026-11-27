package com.supportforge.reranker;

import java.util.List;

/** 精排模型Port；业务层不依赖vLLM/Cohere兼容响应格式。 */
public interface RerankerProvider {
    RerankBatch rerank(String query, List<String> documents);
}
