package com.supportforge.persistence.port;

import com.supportforge.intent.IntentCategory;
import com.supportforge.intent.IntentLearningSample;

import java.util.List;


public interface IntentLearningPersistencePort {

    List<IntentLearningSample> findAll();

    boolean upsert(String message, IntentCategory correctIntent);

    default String scopeKey() { return "local"; }
}
