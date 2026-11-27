package com.supportforge.embedding;

/** 远程协议、维度漂移、网络或响应数据错误。索引流程必须失败，不能用空向量伪装成功。 */
public class EmbeddingProviderException
        extends RuntimeException {

    public EmbeddingProviderException(String message) {
        super(message);
    }

    public EmbeddingProviderException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
