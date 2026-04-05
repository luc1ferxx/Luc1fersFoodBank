package com.laioffer.onlineorder.hello;




import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
public class HelloController {


    @GetMapping("/hello")
    public Person sayHello(@RequestParam(required = false) String name) {
        if (name == null) {
            name = "Guest";
        }
        return new Person(
                name,
                "laioffer",
                new Address("123 Happy Street", "San Francisco", "California", "US"),
                new Book("Clean Code", "John Smith")
        );
    }
}
