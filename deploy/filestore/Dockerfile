# Keycloak 26.7.0 + the filestore extension (the migration demo's starting point, standing in
# for an existing filestore installation). Filestore resolves ${VAR} placeholders in its files
# from environment variables via commons-text, which the extension does not bundle and Keycloak
# does not ship - both jars go into providers/.
FROM quay.io/keycloak/keycloak:26.7.0

ARG FILESTORE_VERSION=3.0.1
ARG COMMONS_TEXT_VERSION=1.15.0
ARG COMMONS_LANG3_VERSION=3.19.0

ADD --chmod=0644 https://repo1.maven.org/maven2/de/arbeitsagentur/opdt/keycloak-extension-filestore/${FILESTORE_VERSION}/keycloak-extension-filestore-${FILESTORE_VERSION}.jar /opt/keycloak/providers/
ADD --chmod=0644 https://repo1.maven.org/maven2/org/apache/commons/commons-text/${COMMONS_TEXT_VERSION}/commons-text-${COMMONS_TEXT_VERSION}.jar /opt/keycloak/providers/
ADD --chmod=0644 https://repo1.maven.org/maven2/org/apache/commons/commons-lang3/${COMMONS_LANG3_VERSION}/commons-lang3-${COMMONS_LANG3_VERSION}.jar /opt/keycloak/providers/

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
