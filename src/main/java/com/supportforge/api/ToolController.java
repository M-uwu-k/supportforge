package com.supportforge.api;

import com.supportforge.api.dto.ToolCallRequest;
import com.supportforge.tool.ToolContext;
import com.supportforge.tool.ToolManager;
import com.supportforge.tool.ToolResult;
import com.supportforge.tool.ToolSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import com.supportforge.tool.ToolStatsSnapshot;
import java.util.Map;


@RestController
@RequestMapping("/tools")
public class ToolController {


    private final ToolManager toolManager;


    public ToolController(
            ToolManager toolManager
    ) {
        this.toolManager = toolManager;
    }


    @GetMapping
    public List<ToolSummary> list() {
        return toolManager.list();
    }


    @PostMapping("/{toolName}/call")
    public ToolResult call(
            @PathVariable
            String toolName,

            @RequestBody
            ToolCallRequest request
    ) {

        ToolContext context =
                new ToolContext(
                        request.userId(),
                        request.conversationId()
                );


        return toolManager.call(
                toolName,
                request.parameters(),
                context
        );
    }


    @GetMapping("/stats")
    public Map<String, ToolStatsSnapshot> stats() {
        return toolManager.stats();
    }
}
