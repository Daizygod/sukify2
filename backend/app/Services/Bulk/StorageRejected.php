<?php

namespace AppServicesBulk;

use RuntimeException;

/** Хранилище отказалось принять файл: кончилось место в бакете или нет доступа. */
class StorageRejected extends RuntimeException
{
}
