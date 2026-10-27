package fr.elhadi.raglearning.controllers;

import fr.elhadi.raglearning.models.RetrievedChunk;
import fr.elhadi.raglearning.services.QuestionAnsweringService;
import fr.elhadi.raglearning.services.RetrievalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/rag")
@RequiredArgsConstructor
public class RagController {

    private final RetrievalService retrievalService;

    private final QuestionAnsweringService questionAnsweringService;

    @GetMapping("/related-chunks")
    public List<RetrievedChunk> findRelatedChunksTo(@RequestParam("userRequest") final String userRequest) {

        return retrievalService.findRelatedChunksTo(userRequest);
    }

    @GetMapping("/ask")
    public ResponseEntity<Set<String>> askAbout(@RequestParam("userRequest") final String userRequest) {

        return ResponseEntity.ok(questionAnsweringService.askAbout(userRequest));
    }
}
