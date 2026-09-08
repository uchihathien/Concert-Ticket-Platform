FROM rabbitmq:3.13-management-alpine

# Plugin delayed message: cần cho retry backoff 5s/30s/2m/10m/1h (ADR-1009 §3).
# RabbitMQ không có sẵn plugin này nên phải tự dựng image.
ARG DELAYED_VERSION=3.13.0
ADD --chmod=644 \
    https://github.com/rabbitmq/rabbitmq-delayed-message-exchange/releases/download/v${DELAYED_VERSION}/rabbitmq_delayed_message_exchange-${DELAYED_VERSION}.ez \
    /opt/rabbitmq/plugins/

RUN rabbitmq-plugins enable --offline rabbitmq_delayed_message_exchange
