package com.jingyue;
import org.springframework.web.bind.annotation.*;
@RestController
public class MetricsController {
    final ApiMetrics metrics;
    public MetricsController(ApiMetrics metrics){this.metrics=metrics;}
    @GetMapping("/api/v1/admin/metrics") public Object read(){Identity.role("ADMIN");return metrics.snapshot();}
}
