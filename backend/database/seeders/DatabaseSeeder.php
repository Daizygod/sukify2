<?php

namespace Database\Seeders;

use App\Enums\ProcessingStatus;
use App\Enums\ReleaseType;
use App\Models\Artist;
use App\Models\Playlist;
use App\Models\Release;
use App\Models\Track;
use App\Models\User;
use Illuminate\Database\Seeder;
use Illuminate\Support\Facades\Hash;
use Illuminate\Support\Str;

class DatabaseSeeder extends Seeder
{
    public function run(): void
    {
        // --- Users ---------------------------------------------------------
        $admin = User::updateOrCreate(
            ['email' => 'admin@sukify.test'],
            [
                'name' => 'Sukify Admin',
                'username' => 'admin',
                'password' => Hash::make('password'),
                'is_admin' => true,
            ]
        );
        $admin->settings();

        $demo = User::updateOrCreate(
            ['email' => 'demo@sukify.test'],
            [
                'name' => 'daizygod',
                'username' => 'daizygod',
                'password' => Hash::make('password'),
            ]
        );
        $demo->settings();

        // --- Catalog -------------------------------------------------------
        $catalog = [
            [
                'name' => 'Metro Boomin',
                'colors' => ['#3b2f5e', '#FFFFFF'],
                'releases' => [
                    [
                        'title' => 'HEROES & VILLAINS',
                        'type' => ReleaseType::Album,
                        'date' => '2022-12-02',
                        'colors' => ['#5a1e1e', '#FFFFFF'],
                        'tracks' => ['On Time', 'Superhero (Heroes & Villains)', 'Too Many Nights', 'Trance', 'Raindrops (Insane)', 'Creepin\''],
                    ],
                    [
                        'title' => 'ACROSS THE SPIDER-VERSE',
                        'type' => ReleaseType::Compilation,
                        'date' => '2023-06-02',
                        'colors' => ['#7a1f4b', '#FFFFFF'],
                        'tracks' => ['Annihilate', 'Am I Dreaming', 'Calling', 'Link Up', 'Silk and Cologne'],
                    ],
                ],
            ],
            [
                'name' => 'Luna Waves',
                'colors' => ['#1e4f5a', '#FFFFFF'],
                'releases' => [
                    [
                        'title' => 'Midnight Currents',
                        'type' => ReleaseType::Album,
                        'date' => '2024-03-15',
                        'colors' => ['#12303a', '#FFFFFF'],
                        'tracks' => ['Undertow', 'Neon Tide', 'Slow Drift', 'Afterglow', 'Harbor Lights'],
                    ],
                    [
                        'title' => 'Static',
                        'type' => ReleaseType::Single,
                        'date' => '2024-08-01',
                        'colors' => ['#403a12', '#FFFFFF'],
                        'tracks' => ['Static'],
                    ],
                ],
            ],
            [
                'name' => 'The Paper Kites',
                'colors' => ['#4a3b2a', '#FFFFFF'],
                'releases' => [
                    [
                        'title' => 'Golden Hour',
                        'type' => ReleaseType::Album,
                        'date' => '2023-10-20',
                        'colors' => ['#6b4a1e', '#FFFFFF'],
                        'tracks' => ['Fields of Gold', 'Willow', 'Paper Moon', 'Quiet Town', 'Riverbank'],
                    ],
                ],
            ],
        ];

        $allTracks = [];

        foreach ($catalog as $artistData) {
            $artist = Artist::updateOrCreate(
                ['slug' => Str::slug($artistData['name'])],
                [
                    'name' => $artistData['name'],
                    'bio' => $artistData['name'].' — sample seeded artist for development.',
                    'dominant_color_hex' => $artistData['colors'][0],
                    'text_color_hex' => $artistData['colors'][1],
                    'monthly_listeners' => random_int(500_000, 60_000_000),
                ]
            );

            foreach ($artistData['releases'] as $releaseData) {
                $release = Release::updateOrCreate(
                    ['slug' => Str::slug($artistData['name'].' '.$releaseData['title'])],
                    [
                        'artist_id' => $artist->id,
                        'title' => $releaseData['title'],
                        'type' => $releaseData['type'],
                        'release_date' => $releaseData['date'],
                        'cover_status' => ProcessingStatus::Pending,
                        'dominant_color_hex' => $releaseData['colors'][0],
                        'text_color_hex' => $releaseData['colors'][1],
                    ]
                );

                foreach ($releaseData['tracks'] as $i => $title) {
                    $track = Track::updateOrCreate(
                        ['release_id' => $release->id, 'track_number' => $i + 1],
                        [
                            'title' => $title,
                            'duration_ms' => random_int(140, 320) * 1000,
                            'loudness_lufs' => -1 * (random_int(70, 130) / 10),
                            'processing_status' => ProcessingStatus::Ready,
                            'plays_count' => random_int(1_000, 5_000_000),
                            'likes_count' => random_int(100, 900_000),
                        ]
                    );

                    $track->artists()->syncWithoutDetaching([
                        $artist->id => ['role' => 'main', 'position' => 0],
                    ]);

                    $allTracks[] = $track;
                }
            }
        }

        // --- A demo public playlist ---------------------------------------
        $playlist = Playlist::updateOrCreate(
            ['user_id' => $demo->id, 'title' => 'электрогитарка'],
            ['description' => 'sample seeded playlist', 'is_public' => true]
        );

        $picks = collect($allTracks)->shuffle()->take(10)->values();
        $playlist->tracks()->detach();
        foreach ($picks as $pos => $track) {
            $playlist->tracks()->attach($track->id, [
                'position' => $pos + 1,
                'added_by_user_id' => $demo->id,
                'added_at' => now(),
            ]);
        }

        // A few likes for the demo user so Liked Songs isn't empty.
        $demo->likedTracks()->syncWithoutDetaching(
            collect($allTracks)->shuffle()->take(15)->pluck('id')->all()
        );

        $this->command?->info('Seeded '.count($allTracks).' tracks across '.count($catalog).' artists.');
    }
}
