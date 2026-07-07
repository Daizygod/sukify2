<?php

namespace App\Enums;

enum SessionRole: string
{
    case Host = 'host';
    case Guest = 'guest';
}
