package fixtures.meta;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.web.bind.annotation.GetMapping;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@GetMapping
public @interface ApiGet {
    String value() default "";
}
