package bean;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JavaConfig {
    @Bean(name = {"nameJ"}) public BeanA createBeanA() { return new BeanA(); }
}