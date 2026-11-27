package com.supportforge.api;

import com.supportforge.memory.ConversationMemory;
import com.supportforge.memory.MemoryMessage;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/conversations")
public class ConversationController {

    private final ConversationMemory conversationMemory;


    public ConversationController(
            ConversationMemory conversationMemory
    ) {
        this.conversationMemory =
                conversationMemory;
    }


    @GetMapping("/{conversationId}/messages")
    public List<MemoryMessage> getMessages(
            @PathVariable
            String conversationId,

            @RequestParam(
                    name = "user_id",
                    defaultValue = "anonymous"
            )
            String userId
    ) {


        return conversationMemory.getRecent(
                userId,
                conversationId
        );
    }


    @DeleteMapping("/{conversationId}/messages")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearMessages(
            @PathVariable
            String conversationId,

            @RequestParam(
                    name = "user_id",
                    defaultValue = "anonymous"
            )
            String userId
    ) {


        conversationMemory.clear(
                userId,
                conversationId
        );
    }
}
