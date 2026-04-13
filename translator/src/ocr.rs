#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub struct Rect {
    pub left: i32,
    pub top: i32,
    pub right: i32,
    pub bottom: i32,
}

impl Rect {
    pub fn width(&self) -> i32 {
        self.right - self.left
    }

    pub fn height(&self) -> i32 {
        self.bottom - self.top
    }

    pub fn center_y(&self) -> i32 {
        (self.top + self.bottom) / 2
    }

    pub fn is_empty(&self) -> bool {
        self.left >= self.right || self.top >= self.bottom
    }

    pub fn union(&mut self, other: Self) {
        if self.is_empty() {
            *self = other;
            return;
        }

        self.left = self.left.min(other.left);
        self.top = self.top.min(other.top);
        self.right = self.right.max(other.right);
        self.bottom = self.bottom.max(other.bottom);
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum ReadingOrder {
    #[default]
    LeftToRight,
    TopToBottomLeftToRight,
}

#[derive(Debug, Clone, PartialEq)]
pub struct DetectedWord {
    pub text: String,
    pub confidence: f32,
    pub bounding_box: Rect,
    pub is_at_beginning_of_para: bool,
    pub end_para: bool,
    pub end_line: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TextLine {
    pub text: String,
    pub bounding_box: Rect,
    pub word_rects: Vec<Rect>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TextBlock {
    pub lines: Vec<TextLine>,
}
