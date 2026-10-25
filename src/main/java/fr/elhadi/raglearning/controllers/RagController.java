package fr.elhadi.raglearning.controllers;

import fr.elhadi.raglearning.models.RagAnswer;
import fr.elhadi.raglearning.services.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagService ragService;

    @GetMapping("/related-chunks")
    public List<RagAnswer> findRelatedChunksTo(@RequestParam("userRequest") final String userRequest) {

        return ragService.findRelatedChunksTo(userRequest);
    }
}
