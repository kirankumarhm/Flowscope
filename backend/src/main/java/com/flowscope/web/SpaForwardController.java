package com.flowscope.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards non-API, non-asset routes to the SPA entry point so client-side
 * navigation resolves to the React app served from {@code META-INF/resources/}.
 */
@Controller
public class SpaForwardController {

    // Root and single-segment paths without a file extension (e.g. "/", "/about").
    @GetMapping({"/", "/{path:[^\\.]*}"})
    public String forward() {
        return "forward:/index.html";
    }
}
