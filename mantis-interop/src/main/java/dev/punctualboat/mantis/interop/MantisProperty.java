package dev.punctualboat.mantis.interop;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface MantisProperty { String value(); }
