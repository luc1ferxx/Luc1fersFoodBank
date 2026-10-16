package com.laioffer.onlineorder.fulfilment.merchant;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;


public record RejectOrderBody(
        @NotBlank
        @Size(max = 200)
        String reason
) {
}
