# app/utils/helpers.py
"""공통 유틸리티 함수들"""

import pandas as pd
import numpy as np


def calculate_rsi(series, period=14):
    """RSI (Relative Strength Index) 계산"""
    delta = series.diff(1)
    gain = (delta.where(delta > 0, 0)).rolling(window=period).mean()
    loss = (-delta.where(delta < 0, 0)).rolling(window=period).mean()
    rs = gain / loss
    return 100 - (100 / (1 + rs))


