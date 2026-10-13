package com.nequi.ticketing.infrastructure.web;

import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

final class WebTestSupport {

    private WebTestSupport() {}

    static LocalValidatorFactoryBean validator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }
}
