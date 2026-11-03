package dev.javaharness.server.web;

import dev.javaharness.adapters.question.QuestionService;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 查询运行中模型向用户提出的问题，并提交回答或跳过。 */
@RestController
@RequestMapping("/api")
public class QuestionController {
    public record AnswerRequest(List<String> selected, String note) {}

    private final QuestionService questions;

    public QuestionController(QuestionService questions) {
        this.questions = questions;
    }

    @GetMapping("/runs/{id}/questions")
    public List<QuestionService.Question> list(@PathVariable String id) {
        return questions.list(id);
    }

    @PostMapping(value = "/questions/{id}/answer", consumes = MediaType.APPLICATION_JSON_VALUE)
    public QuestionService.Question answer(@PathVariable String id, @RequestBody AnswerRequest request) {
        return questions.answer(id, request.selected(), request.note());
    }

    @PostMapping(value = "/questions/{id}/skip", consumes = MediaType.APPLICATION_JSON_VALUE)
    public QuestionService.Question skip(@PathVariable String id) {
        return questions.skip(id);
    }
}
