//! The daily question. Both phones pick the same question for the same day
//! from this list, so it works offline and needs no server. Answers travel
//! end-to-end encrypted; each partner sees the other's answer only after
//! answering too.

/// One question of the day.
#[derive(uniffi::Record, Clone, Debug, PartialEq, Eq)]
pub struct DailyQuestion {
    /// Stable id, so answers stay matched to their question.
    pub id: String,
    pub text: String,
    /// Which day (days since 1970, UTC) it is the question for.
    pub day: i64,
}

const DAY_MS: i64 = 24 * 60 * 60 * 1000;

/// The questions, light and deep mixed. Never reorder or remove: ids are the
/// position in this list. Add new ones at the end.
const QUESTIONS: &[&str] = &[
    "What's a small thing I do that makes you feel loved?",
    "Which day with me would you happily live again?",
    "What did you think of me the very first time we met?",
    "If we could teleport anywhere for dinner tonight, where would we go?",
    "What's one thing you'd love us to try together this year?",
    "Which song makes you think of me?",
    "What's your favourite photo of us, and why?",
    "When do you feel closest to me?",
    "What's a habit of mine you secretly find cute?",
    "If our love story were a film, what would it be called?",
    "What's something you've never told me about your childhood?",
    "How do you like to be comforted when you're sad?",
    "What's the best surprise anyone ever gave you?",
    "Which of my friends would you trust with a secret?",
    "What's a dream you haven't said out loud yet?",
    "What would our perfect lazy Sunday look like?",
    "What's one thing that always makes you laugh about us?",
    "What did you want to be when you grew up?",
    "Which food reminds you of home?",
    "What's something you're proud of this week?",
    "If you could relive one of our dates, which one?",
    "What's a promise you'd like us to make each other?",
    "How do you know when I'm upset, even if I don't say it?",
    "What's the most romantic thing you can imagine us doing?",
    "Which place do you most want to show me?",
    "What made you fall for me?",
    "What's your love language, and has it changed?",
    "What's a tradition you'd like us to start?",
    "Which three words describe us best?",
    "What's something new you learned about me recently?",
    "When did you last feel really proud of me?",
    "What would you put in a time capsule about us?",
    "If you could ask my younger self one question, what would it be?",
    "What's the silliest argument we've had?",
    "What's one thing I could do this week to make your day easier?",
    "Where do you see us in five years?",
    "What's your favourite way to spend a rainy day with me?",
    "What smell reminds you of me?",
    "What's a compliment you'd like to hear more often?",
    "Which movie should we watch together next?",
    "What's a fear you'd like me to understand better?",
    "What does home mean to you?",
    "What's the kindest thing a stranger has ever done for you?",
    "If we wrote a bucket list together, what's number one?",
    "What's your favourite thing about our conversations?",
    "What was the best part of your day today?",
    "What's something you want to get better at?",
    "Which nickname for me is your favourite?",
    "If we had a whole day with no phones, what would we do?",
    "What do you think we're best at as a couple?",
    "What's one thing that instantly puts you in a good mood?",
    "Which song would be our first-dance song?",
    "What's a memory of us that still makes you smile?",
    "How would your best friend describe us?",
    "What's something small you'd like more of from me?",
    "What's the best advice anyone's given you about love?",
    "Which season feels most like us, and why?",
    "What's a skill you'd love to learn together?",
    "What's one thing you'd never change about me?",
    "If we opened a little shop together, what would it sell?",
    "What's the most adventurous thing you'd do with me?",
    "Which of your family traditions do you love most?",
    "What does a perfect morning look like for you?",
    "What's something you're looking forward to?",
    "What's the sweetest message I've ever sent you?",
    "When you miss me, what do you miss most?",
    "What's one thing you'd like us to do more often?",
    "What's a book, film or show that changed you?",
    "If we had a pet together, what would we name it?",
    "What's your happiest memory from this year?",
    "What do you need most when you're stressed?",
    "What's the funniest thing that happened to you this month?",
    "If you could have dinner with anyone, living or not, who?",
    "Which little moment today made you think of me?",
    "What's one thing you've forgiven me for?",
    "What's something you'd like to hear me say more?",
    "What does a good apology look like to you?",
    "Where would you like to go on our next trip?",
    "What's a goal we could work on together?",
    "What's the best gift you've ever received from me?",
    "What would you cook me on a special day?",
    "How do you want to celebrate our next anniversary?",
    "What's something you appreciate about how I treat you?",
    "Which of your quirks do you hope I never stop loving?",
    "What's a question you've always wanted to ask me?",
    "What's the bravest thing you've done?",
    "What did you dream about recently?",
    "What's one rule you'd make for our home?",
    "When did you first know this was serious?",
    "What's your favourite way to say \"I love you\" without words?",
    "Which emoji is most \"us\"?",
    "What would you like to thank me for today?",
    "What's something that's been on your mind lately?",
    "If we swapped lives for a day, what would you do first?",
    "What's the best date we haven't been on yet?",
    "What's a simple pleasure you never get tired of?",
    "Which of our inside jokes is your favourite?",
    "How can I support your dreams better?",
    "What would you like to be remembered for?",
    "What's a place that feels peaceful to you?",
    "If we had a theme song, what would it be?",
    "What's the most \"you\" thing you did this week?",
    "What's one worry I can take off your mind?",
    "What do you love about where you grew up?",
    "What would make next weekend perfect?",
    "Which talent of mine do you secretly envy?",
    "What's a moment you felt completely understood by me?",
    "What's your idea of a perfect gift?",
    "What's one thing we've learned from each other?",
    "If you wrote me a letter today, how would it start?",
    "What's a challenge we got through that made us stronger?",
    "What's your favourite way to fall asleep?",
    "Which festival or holiday do you love spending with me?",
    "What tiny detail about me do you notice that others don't?",
    "What are you most grateful for right now?",
    "What's the next adventure you want for us?",
    "If today had a colour, what colour would it be?",
    "What do you hope we never stop doing?",
    "What do you love most about us, right now?",
];

/// The day number (days since 1970, UTC) at this moment. Both phones agree
/// on it, wherever they are.
#[uniffi::export]
pub fn question_day(now_ms: i64) -> i64 {
    now_ms.div_euclid(DAY_MS)
}

/// The question for a given day. Days are spread over the whole list so
/// neighbouring days never get neighbouring questions.
#[uniffi::export]
pub fn daily_question(day: i64) -> DailyQuestion {
    let count = QUESTIONS.len() as i64;
    // 37 shares no factor with the list length, so every question comes up once per cycle.
    let index = (day.rem_euclid(count) * 37).rem_euclid(count) as usize;
    DailyQuestion {
        id: format!("q{index:03}"),
        text: QUESTIONS[index].to_owned(),
        day,
    }
}

/// The question with this id, for showing old answers.
#[uniffi::export]
pub fn question_text(id: String) -> Option<String> {
    let index: usize = id.strip_prefix('q')?.parse().ok()?;
    QUESTIONS.get(index).map(|q| (*q).to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_question_comes_up_once_per_cycle() {
        let count = QUESTIONS.len() as i64;
        assert_eq!(gcd(37, count), 1, "37 must not share a factor with {count}");
        let mut seen = std::collections::HashSet::new();
        for day in 20_000..20_000 + count {
            assert!(seen.insert(daily_question(day).id));
        }
        assert_eq!(seen.len(), QUESTIONS.len());
    }

    #[test]
    fn both_phones_agree_on_the_day_and_question() {
        // 23:30 UTC and 00:30 UTC the next day are different days everywhere.
        let late = 1_767_311_400_000; // 2026-01-01 23:50 UTC
        assert_eq!(question_day(late), question_day(late - 60_000));
        assert_eq!(question_day(late + 20 * 60_000), question_day(late) + 1);
        let q = daily_question(question_day(late));
        assert_eq!(question_text(q.id.clone()), Some(q.text));
        assert_eq!(question_text("nope".into()), None);
        assert_eq!(question_text("q999".into()), None);
    }

    fn gcd(a: i64, b: i64) -> i64 {
        if b == 0 { a } else { gcd(b, a % b) }
    }
}
