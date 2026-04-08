package com.brunosong.system.chatting.adapter.in.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

@Controller
public class ViewController {

    @GetMapping("/")
    public String home() {
        return "home";
    }

    @GetMapping("/client")
    public String client(@RequestParam(required = false) String name, Model model) {
        if (name == null || name.isBlank()) {
            return "client-form";
        }
        model.addAttribute("name", name);
        model.addAttribute("roomId", UUID.randomUUID().toString());
        model.addAttribute("role", "client");
        return "chat";
    }

    @GetMapping("/admin")
    public String admin() {
        return "admin-list";
    }

    @GetMapping("/admin/chat")
    public String adminChat(@RequestParam String roomId, Model model) {
        model.addAttribute("name", "관리자");
        model.addAttribute("roomId", roomId);
        model.addAttribute("role", "admin");
        return "chat";
    }
}
