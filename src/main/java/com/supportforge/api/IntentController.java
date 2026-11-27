package com.supportforge.api;

import com.supportforge.api.dto.IntentLearningRequest;
import com.supportforge.api.dto.IntentLearningResponse;
import com.supportforge.intent.IntentCacheStats;
import com.supportforge.intent.IntentCategory;
import com.supportforge.intent.IntentRecognizer;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/intents")
@com.supportforge.security.RequiresAnyRole(com.supportforge.security.EnterpriseRole.ADMIN)
public class IntentController {


    private final IntentRecognizer intentRecognizer;


    public IntentController(
            IntentRecognizer intentRecognizer
    ) {
        this.intentRecognizer =
                intentRecognizer;
    }


    @PostMapping("/learn")
    public IntentLearningResponse learn(
            @RequestBody
            IntentLearningRequest request
    ) {

        IntentCategory category =
                request.category();


        boolean learned =
                intentRecognizer.learn(
                        request.message(),
                        category
                );


        return new IntentLearningResponse(
                learned,
                request.message(),
                category.value(),
                intentRecognizer
                        .learnedTemplateCount(),
                intentRecognizer.cacheStats()
        );
    }


    @GetMapping("/cache/stats")
    @com.supportforge.security.RequiresAnyRole({com.supportforge.security.EnterpriseRole.ADMIN,
            com.supportforge.security.EnterpriseRole.AGENT, com.supportforge.security.EnterpriseRole.AUDITOR})
    public IntentCacheStats cacheStats() {
        return intentRecognizer.cacheStats();
    }


    @DeleteMapping("/cache")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearCache() {
        intentRecognizer.clearCache();
    }
}
