<?php

namespace App\Enums;

enum ReleaseType: string
{
    case Album = 'album';
    case Single = 'single';
    case Compilation = 'compilation';
}
