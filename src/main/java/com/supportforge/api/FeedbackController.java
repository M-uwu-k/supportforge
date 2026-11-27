package com.supportforge.api;

import com.supportforge.api.dto.FeedbackRequest;
import com.supportforge.feedback.AnswerFeedback;
import com.supportforge.feedback.FeedbackService;
import com.supportforge.feedback.FeedbackSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/feedback")
public class FeedbackController {

    private final FeedbackService feedbackService;


    public FeedbackController(
            FeedbackService feedbackService
    ) {
        this.feedbackService = feedbackService;
    }


    @PostMapping
    public AnswerFeedback submit(

            @RequestBody FeedbackRequest request
    ) {
        return feedbackService.submit(request);
    }


    @GetMapping
    public List<AnswerFeedback> findAll(
            @RequestParam(
                    name = "conversation_id",
                    required = false
            )
            String conversationId
    ) {
        return feedbackService.findAll(
                conversationId
        );
    }


    @GetMapping("/summary")
    public FeedbackSummary summary(
            @RequestParam(
                    name = "conversation_id",
                    required = false
            )
            String conversationId
    ) {
        return feedbackService.summary(
                conversationId
        );
    }
}
