package com.foundry.gym.web;

import com.foundry.gym.dto.Dtos.ApiError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Someone opening the backend address in a browser is told where the UI lives. */
@RestController
public class RootController {

    @GetMapping("/")
    public ApiError root() {
        return new ApiError("This is the Foundry gym API. Open the UI, by default at http://localhost:5173.");
    }
}
