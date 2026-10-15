<?php

namespace Tests\Feature;

use Tests\TestCase;

class ExampleTest extends TestCase
{
    /** Корень бэкенда ведёт в админку — SPA живёт отдельно. */
    public function test_root_redirects_to_admin(): void
    {
        $this->get('/')->assertRedirect('/admin');
    }
}
