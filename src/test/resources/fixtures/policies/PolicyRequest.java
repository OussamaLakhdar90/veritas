package fixtures.policies;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PolicyRequest(@NotBlank String name, @Size(max = 10) String code) {
}
