<?php

namespace App\Enums;

enum CurveType: string
{
    case Linear = 'linear';
    case Exponential = 'exponential';
    case Logarithmic = 'logarithmic';
    case SCurve = 's_curve';
}
