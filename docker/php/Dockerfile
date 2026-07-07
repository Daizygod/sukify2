FROM php:8.4-fpm-bookworm

# --- System deps -----------------------------------------------------------
# ffmpeg      -> audio pipeline (transcode, loudnorm, ffprobe)
# libvips/... -> image processing (Intervention Image can use imagick)
# imagemagick -> imagick ext + league/color-extractor
RUN apt-get update && apt-get install -y --no-install-recommends \
        git curl unzip \
        ffmpeg \
        libpq-dev \
        libzip-dev \
        libicu-dev \
        libpng-dev libjpeg-dev libfreetype6-dev libwebp-dev \
        libmagickwand-dev \
        libonig-dev \
    && rm -rf /var/lib/apt/lists/*

# --- PHP extensions --------------------------------------------------------
RUN docker-php-ext-configure gd --with-freetype --with-jpeg --with-webp \
    && docker-php-ext-install -j"$(nproc)" \
        pdo_pgsql pgsql \
        gd \
        intl \
        zip \
        bcmath \
        exif \
        pcntl \
        opcache

# PECL extensions: redis + imagick
RUN pecl install redis \
    && (pecl install imagick || pecl install imagick-3.7.0) \
    && docker-php-ext-enable redis imagick

# --- Composer --------------------------------------------------------------
COPY --from=composer:2 /usr/bin/composer /usr/bin/composer

# --- PHP config ------------------------------------------------------------
COPY docker/php/php.ini /usr/local/etc/php/conf.d/zz-app.ini
COPY docker/php/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

WORKDIR /var/www/html

# Match host user to avoid permission issues on bind-mounted volumes (Linux hosts).
# On Docker Desktop/Windows this is harmless.
ARG UID=1000
ARG GID=1000
RUN groupmod -g ${GID} www-data 2>/dev/null || true \
    && usermod -u ${UID} www-data 2>/dev/null || true

ENTRYPOINT ["entrypoint.sh"]
CMD ["php-fpm"]
