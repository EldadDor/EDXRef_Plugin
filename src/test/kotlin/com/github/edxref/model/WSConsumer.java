package com.github.edxref.model;

public @interface WSConsumer {
    String url() default "";
    String path() default "";
    WSMethods method();
    long timeout() default 0;
    WSMsConsumer msConsumer() default @WSMsConsumer(LbMsType.NONE);
}