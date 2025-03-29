package com.github.edxref.inspection;

import com.intellij.codeInspection.*;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

// Java implementation
public class WSConsumerJavaInspection extends AbstractBaseJavaLocalInspectionTool implements WSConsumerInspectionLogic {
    @Override
    public String getDisplayName() {
        return "WSConsumer annotation inspection (Java)";
    }

    @Override
    public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly,
                                          @NotNull LocalInspectionToolSession session) {
        return new JavaElementVisitor() {
            @Override
            public void visitAnnotation(@NotNull PsiAnnotation annotation) {
                if (!annotation.getQualifiedName().endsWith("WSConsumer")) {
                    return;
                }

                // Get the annotated element
                PsiModifierList modifierList = (PsiModifierList) annotation.getParent();
                if (modifierList == null) return;

                PsiModifierListOwner annotatedElement = (PsiModifierListOwner) modifierList.getParent();
                if (annotatedElement == null) return;

                // Check if annotated element implements PearlWebserviceConsumer
                boolean isPearlWebserviceConsumer = isPearlWebserviceConsumer(annotatedElement);

                // Extract annotation attributes
                PsiAnnotationMemberValue urlAttr = annotation.findAttributeValue("url");
                String urlValue = "";
                if (urlAttr != null && urlAttr instanceof PsiLiteralExpression) {
                    Object urlObj = ((PsiLiteralExpression) urlAttr).getValue();
                    if (urlObj != null) {
                        urlValue = urlObj.toString();
                    }
                }

                PsiAnnotationMemberValue pathAttr = annotation.findAttributeValue("path");
                String pathValue = "";
                if (pathAttr != null && pathAttr instanceof PsiLiteralExpression) {
                    Object pathObj = ((PsiLiteralExpression) pathAttr).getValue();
                    if (pathObj != null) {
                        pathValue = pathObj.toString();
                    }
                }

                // Handle sslCertificateValidation - default is true if not specified
                PsiAnnotationMemberValue sslValue = annotation.findAttributeValue("sslCertificateValidation");
                boolean sslCertificateValidation = true;
                if (sslValue instanceof PsiLiteralExpression) {
                    Object sslObj = ((PsiLiteralExpression) sslValue).getValue();
                    if (sslObj instanceof Boolean) {
                        sslCertificateValidation = (Boolean) sslObj;
                    }
                }

                // Check for msConsumer
                PsiAnnotationMemberValue msConsumerAttr = annotation.findAttributeValue("msConsumer");
                boolean hasMsConsumer = msConsumerAttr != null;
                String msConsumerValue = hasMsConsumer ? msConsumerAttr.getText() : "";

                // Use the common logic
                checkWSConsumerAnnotation(
                        annotation,
                        holder,
                        urlValue,
                        pathValue,
                        sslCertificateValidation,
                        hasMsConsumer,
                        msConsumerValue,
                        isPearlWebserviceConsumer
                );
            }

            private boolean isPearlWebserviceConsumer(PsiModifierListOwner element) {
                if (!(element instanceof PsiClass)) return false;
                PsiClass psiClass = (PsiClass) element;

                // Check implemented interfaces
                PsiClass[] interfaces = psiClass.getInterfaces();
                for (PsiClass anInterface : interfaces) {
                    String qualifiedName = anInterface.getQualifiedName();
                    if (qualifiedName != null && qualifiedName.endsWith("PearlWebserviceConsumer")) {
                        return true;
                    }
                }

                // Check superclass hierarchy
                PsiClass superClass = psiClass.getSuperClass();
                while (superClass != null) {
                    String qualifiedName = superClass.getQualifiedName();
                    if (qualifiedName != null && qualifiedName.endsWith("PearlWebserviceConsumer")) {
                        return true;
                    }
                    superClass = superClass.getSuperClass();
                }

                return false;
            }
        };
    }

    // Additional implementation of WSConsumerInspectionLogic
    @Override
    public void checkWSConsumerAnnotation(
            PsiElement annotationElement,
            ProblemsHolder holder,
            String urlValue,
            String pathValue,
            boolean sslCertificateValidation,
            boolean hasMsConsumer,
            String msConsumerValue,
            boolean isPearlWebserviceConsumer
    ) {
        // Rule 1: If msConsumer is present, url should not be specified (use path only)
        if (hasMsConsumer && !urlValue.isEmpty()) {
            holder.registerProblem(
                    annotationElement,
                    "For @WSConsumer with msConsumer, 'url' must not be specified; use 'path' only."
            );
        }

        // Rule 2: The 'path' should not contain protocol information
        if (!pathValue.isEmpty() && (pathValue.contains("http://") || pathValue.contains("https://"))) {
            holder.registerProblem(
                    annotationElement,
                    "The 'path' attribute must not contain http/https; specify only a relative path."
            );
        }

        // Rule 3: URL should not contain double slashes (except in protocol)
        if (!urlValue.isEmpty()) {
            int protocolIndex = urlValue.indexOf("://");
            if (protocolIndex > 0 && urlValue.substring(protocolIndex + 3).contains("//")) {
                holder.registerProblem(
                        annotationElement,
                        "The 'url' attribute contains invalid double slashes."
                );
            }
        }

        // Rule 4: When only 'path' is specified, 'msConsumer' must be defined
        if (!pathValue.isEmpty() && urlValue.isEmpty() && !hasMsConsumer) {
            holder.registerProblem(
                    annotationElement,
                    "When only 'path' is specified, 'msConsumer' must be defined."
            );
        }

        // Rule 5: Detect invalid URLs containing specific hosts
        if (!urlValue.isEmpty()) {
            List<String> invalidHosts = Arrays.asList("msdevcz", "msdevcrm");
            for (String host : invalidHosts) {
                if (urlValue.contains(host)) {
                    holder.registerProblem(
                            annotationElement,
                            "Invalid URL: '" + host + "' is in the list of restricted servers."
                    );
                    break;
                }
            }
        }

        // Rule 6: Further restrictions for PearlWebserviceConsumer
        if (isPearlWebserviceConsumer) {
            if (hasMsConsumer) {
                // Rule 6.1: If a value is provided for msConsumer in PearlWebserviceConsumer, it must be LOCAL
                boolean hasLocal = msConsumerValue.contains("LOCAL");
                if (!msConsumerValue.isEmpty() && !hasLocal) {
                    holder.registerProblem(
                            annotationElement,
                            "When used in PearlWebserviceConsumer, msConsumer value may only be omitted or set to LOCAL."
                    );
                } else if (hasLocal && sslCertificateValidation) {
                    // Rule 6.2: For LOCAL msConsumer in PearlWebserviceConsumer, sslCertificateValidation must be false
                    holder.registerProblem(
                            annotationElement,
                            "For PearlWebserviceConsumer with msConsumer set to LOCAL, sslCertificateValidation must be false."
                    );
                }
            }
        } else {
            // Rule 7: Disallow PEARL LbMsType for regular WebserviceConsumer
            if (hasMsConsumer && msConsumerValue.contains("PEARL")) {
                holder.registerProblem(
                        annotationElement,
                        "'PEARL' LbMsType is not allowed for WebserviceConsumer"
                );
            }
        }
    }
}
