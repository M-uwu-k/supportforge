package com.supportforge.retrieval;

import java.util.List;

/** 混合召回输出Port；应用服务不知道Qdrant Query API的JSON结构。 */
public interface HybridRetrievalPort {

    List<HybridSearchHit> search(HybridSearchRequest request);
}
