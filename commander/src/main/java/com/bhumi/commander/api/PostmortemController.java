package com.bhumi.commander.api;

import com.bhumi.commander.incident.PostmortemService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/incidents")
public class PostmortemController {

    private final PostmortemService postmortems;

    public PostmortemController(PostmortemService postmortems) { this.postmortems = postmortems; }

    @GetMapping("/{id}/postmortem")
    public ResponseEntity<String> postmortem(@PathVariable long id,
                                             @RequestParam(defaultValue = "false") boolean ai,
                                             Authentication auth) {
        boolean operator = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_OPERATOR"));
        if (ai && !operator) {
            return ResponseEntity.status(403).body("AI suggestions are limited to operators.");
        }
        return postmortems.generate(id, ai)
                .map(text -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                        .body(text))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}