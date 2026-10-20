package com.dreamapt.common;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;


@RestController 
@RequestMapping("/api")
public class PingController {
    
    @GetMapping("/ping")
    public String ping () {
        return "pong";
    }
    
}
