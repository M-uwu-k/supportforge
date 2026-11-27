package com.supportforge.migration;

record LegacySourceItem(
        LegacyItemKind kind,
        String sourceKey,
        String itemSha256,
        Object payload
) { }
