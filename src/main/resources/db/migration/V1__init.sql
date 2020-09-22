create table users (
    id text not null primary key,
    fetching boolean not null,
    last_fetched timestamp not null
);

create table games (
    id text not null primary key,
    created timestamp not null
);

create table puzzles (
    id text not null primary key,
    user_id_white text not null,
    user_id_black text not null,
    game_id text not null,
    fen text not null,
    orientation text not null,
    url text not null,
    move_number integer not null,
    move_display text not null,
    move_source text not null,
    move_destination text not null,
    created timestamp not null
);
